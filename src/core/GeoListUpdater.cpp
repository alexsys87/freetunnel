// cppcheck-suppress-file missingIncludeSystem
#include "core/GeoListUpdater.h"

#include <QCryptographicHash>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QJsonDocument>
#include <QJsonObject>
#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QPointer>
#include <QRegularExpression>
#include <QSaveFile>
#include <QThreadPool>

#include <algorithm>
#include <chrono>
#include <memory>

namespace freetunnel {

namespace {

// The default sources. runetfreedom publishes Russia's block lists (from
// antifilter.download and re:filter) as V2Ray lists several times a day, with
// a SHA-256 beside each .dat file; the domains also as a plain text list of
// under 2 MB, which spares a daily download of the 75 MB geosite.dat they are
// part of. v2fly's domain-list-community is the source of V2Ray's own
// category-ru, the sites of Russian companies and services; its dlc.dat is a
// couple of megabytes.
const QString kRunetGeoip = QStringLiteral(
        "https://github.com/runetfreedom/russia-blocked-geoip/releases/latest/download/geoip.dat");

constexpr qint64 kMaxChecksumBytes = 4096;

QString sha256Hex(const QByteArray &data)
{
    return QString::fromLatin1(QCryptographicHash::hash(data, QCryptographicHash::Sha256).toHex());
}

// "<64 hex digits>  name" or the digest alone, as sha256sum writes it.
QString checksumFrom(const QByteArray &body)
{
    static const QRegularExpression re(QStringLiteral("^\\s*([0-9a-fA-F]{64})(\\s|$)"));
    const QRegularExpressionMatch m = re.match(QString::fromLatin1(body.left(kMaxChecksumBytes)));
    return m.hasMatch() ? m.captured(1).toLower() : QString();
}

QNetworkRequest listRequest(const QUrl &url, const QString &appVersion)
{
    QNetworkRequest req(url);
    req.setHeader(QNetworkRequest::UserAgentHeader, QStringLiteral("FreeTunnel/%1").arg(appVersion));
    // GitHub's latest/download addresses redirect twice, the second time to
    // another host: https to https is all that is followed.
    req.setAttribute(QNetworkRequest::RedirectPolicyAttribute, QNetworkRequest::NoLessSafeRedirectPolicy);
    req.setMaximumRedirectsAllowed(5);
    return req;
}

QString replyError(const QNetworkReply *reply)
{
    const QNetworkReply::NetworkError error = reply->error();
    return error == QNetworkReply::OperationCanceledError || error == QNetworkReply::TimeoutError
            ? QStringLiteral("the server stopped responding")
            : reply->errorString();
}

// What a worker reads for one slot.
struct SlotJob {
    QString path;
    GeoSource source;
    GeoListKind kind;
    QString id;
};

} // namespace

QString geoRoutingModeName(GeoRoutingMode mode)
{
    switch (mode) {
    case GeoRoutingMode::Blocked: return QStringLiteral("blocked");
    case GeoRoutingMode::ExceptRussia: return QStringLiteral("except_ru");
    case GeoRoutingMode::Off: break;
    }
    return QStringLiteral("off");
}

GeoRoutingMode geoRoutingModeFromName(const QString &name)
{
    if (name == QLatin1String("blocked"))
        return GeoRoutingMode::Blocked;
    if (name == QLatin1String("except_ru"))
        return GeoRoutingMode::ExceptRussia;
    return GeoRoutingMode::Off;
}

GeoDirection geoDirectionFor(GeoRoutingMode mode)
{
    return mode == GeoRoutingMode::ExceptRussia ? GeoDirection::Bypass : GeoDirection::ThroughVpn;
}

const QList<GeoListSlot> &geoListSlots()
{
    static const QList<GeoListSlot> table = {
            {QStringLiteral("blocked_sites"), GeoListKind::Sites, GeoRoutingMode::Blocked,
             QStringLiteral("https://github.com/runetfreedom/russia-blocked-geosite/releases/latest/"
                            "download/ru-blocked.txt")},
            {QStringLiteral("blocked_ips"), GeoListKind::Ips, GeoRoutingMode::Blocked,
             kRunetGeoip + QStringLiteral("#ru-blocked")},
            {QStringLiteral("russia_sites"), GeoListKind::Sites, GeoRoutingMode::ExceptRussia,
             QStringLiteral("https://github.com/v2fly/domain-list-community/releases/latest/"
                            "download/dlc.dat#category-ru")},
            {QStringLiteral("russia_ips"), GeoListKind::Ips, GeoRoutingMode::ExceptRussia,
             kRunetGeoip + QStringLiteral("#ru")},
    };
    return table;
}

const GeoListSlot *geoListSlot(const QString &id)
{
    for (const GeoListSlot &slot : geoListSlots()) {
        if (slot.id == id)
            return &slot;
    }
    return nullptr;
}

GeoListUpdater::GeoListUpdater(const QString &cacheDir, const QString &appVersion, QObject *parent)
    : QObject(parent), m_cacheDir(cacheDir), m_appVersion(appVersion),
      m_nam(new QNetworkAccessManager(this))
{
    // A stalled transfer is given up on; one that is still moving is not.
#if QT_VERSION >= QT_VERSION_CHECK(6, 7, 0)
    m_nam->setTransferTimeout(std::chrono::seconds(60));
#else
    m_nam->setTransferTimeout(60000);
#endif
    m_timer.setSingleShot(true);
    connect(&m_timer, &QTimer::timeout, this, [this]() { startUpdate(false); });
    loadState();
}

GeoListUpdater::~GeoListUpdater()
{
    // A worker still reading holds no pointer to this object: its result is
    // handed back through a QPointer and dropped when this is gone.
    if (m_reply) {
        m_reply->disconnect(this);
        m_reply->abort();
        m_reply->deleteLater();
    }
}

void GeoListUpdater::setSources(const QMap<QString, QString> &sources)
{
    if (sources == m_sources)
        return;
    m_sources = sources;
    if (m_mode == GeoRoutingMode::Off)
        return;
    rebuild();
    startUpdate(false);
}

QString GeoListUpdater::sourceFor(const QString &slotId) const
{
    const QString chosen = m_sources.value(slotId).trimmed();
    if (!chosen.isEmpty())
        return chosen;
    const GeoListSlot *slot = geoListSlot(slotId);
    return slot ? slot->defaultSource : QString();
}

void GeoListUpdater::setMode(GeoRoutingMode mode)
{
    if (mode == m_mode)
        return;
    m_mode = mode;
    rebuild();
    if (mode == GeoRoutingMode::Off) {
        m_timer.stop();
        return;
    }
    startUpdate(false);
}

void GeoListUpdater::setAutoUpdate(bool on)
{
    if (on == m_autoUpdate)
        return;
    m_autoUpdate = on;
    scheduleCheck();
}

void GeoListUpdater::updateNow()
{
    if (m_mode == GeoRoutingMode::Off)
        return;
    startUpdate(true);
}

QStringList GeoListUpdater::neededFiles() const
{
    QStringList files;
    for (const GeoListSlot &slot : geoListSlots()) {
        if (slot.mode != m_mode)
            continue;
        const GeoSource source = parseGeoSource(sourceFor(slot.id));
        const QString file = source.file.toString(QUrl::FullyEncoded);
        if (source.valid() && !files.contains(file))
            files << file;
    }
    return files;
}

QString GeoListUpdater::cachePath(const QString &fileUrl) const
{
    return m_cacheDir + QLatin1Char('/') + sha256Hex(fileUrl.toUtf8()).left(32) + QStringLiteral(".list");
}

QString GeoListUpdater::lastError() const
{
    if (m_lastError.isEmpty() || m_readError.isEmpty())
        return m_lastError + m_readError;
    return m_lastError + QStringLiteral("; ") + m_readError;
}

QDateTime GeoListUpdater::lastChecked() const
{
    QDateTime oldest;
    for (const QString &file : neededFiles()) {
        const QDateTime checked = m_state.value(file).checked;
        if (!checked.isValid() || !QFileInfo::exists(cachePath(file)))
            return {};
        if (!oldest.isValid() || checked < oldest)
            oldest = checked;
    }
    return oldest;
}

void GeoListUpdater::loadState()
{
    QFile f(m_cacheDir + QStringLiteral("/state.json"));
    if (!f.open(QIODevice::ReadOnly))
        return;
    const QJsonObject files = QJsonDocument::fromJson(f.read(1024 * 1024)).object().value("files").toObject();
    for (auto it = files.begin(); it != files.end(); ++it) {
        const QJsonObject o = it.value().toObject();
        CachedFile c;
        c.sha256 = o.value("sha256").toString();
        c.fetched = QDateTime::fromString(o.value("fetched").toString(), Qt::ISODate);
        c.checked = QDateTime::fromString(o.value("checked").toString(), Qt::ISODate);
        m_state.insert(it.key(), c);
    }
}

void GeoListUpdater::saveState() const
{
    QDir().mkpath(m_cacheDir);
    QJsonObject files;
    for (auto it = m_state.cbegin(); it != m_state.cend(); ++it) {
        QJsonObject o;
        o.insert("sha256", it->sha256);
        o.insert("fetched", it->fetched.toUTC().toString(Qt::ISODate));
        o.insert("checked", it->checked.toUTC().toString(Qt::ISODate));
        files.insert(it.key(), o);
    }
    QSaveFile f(m_cacheDir + QStringLiteral("/state.json"));
    if (!f.open(QIODevice::WriteOnly))
        return;
    f.write(QJsonDocument(QJsonObject{{"files", files}}).toJson(QJsonDocument::Compact));
    f.commit();
}

// When the next list is due: now for one never downloaded, an hour after a
// failed attempt, and a day after the last good check while auto-update is on.
void GeoListUpdater::scheduleCheck()
{
    m_timer.stop();
    if (m_mode == GeoRoutingMode::Off || m_reply)
        return;
    const QDateTime now = QDateTime::currentDateTimeUtc();
    QDateTime next;
    for (const QString &file : neededFiles()) {
        QDateTime due;
        if (m_failedAt.contains(file))
            due = m_failedAt.value(file).addSecs(kRetryIntervalSecs);
        else if (!QFileInfo::exists(cachePath(file)) || !m_state.value(file).checked.isValid())
            due = now;
        else if (m_autoUpdate)
            due = m_state.value(file).checked.addSecs(kCheckIntervalSecs);
        if (due.isValid() && (!next.isValid() || due < next))
            next = due;
    }
    if (!next.isValid())
        return;
    // Capped at an hour so a clock that jumps (sleep, a time zone change) is
    // caught up with soon after.
    const qint64 ms = std::clamp<qint64>(now.msecsTo(next), 0, qint64(kRetryIntervalSecs) * 1000);
    m_timer.start(std::chrono::milliseconds(ms));
}

void GeoListUpdater::startUpdate(bool force)
{
    if (m_mode == GeoRoutingMode::Off || m_reply || m_busy)
        return;
    const QDateTime now = QDateTime::currentDateTimeUtc();
    m_queue.clear();
    for (const QString &file : neededFiles()) {
        const bool missing = !QFileInfo::exists(cachePath(file)) || !m_state.value(file).checked.isValid();
        const bool failedRecently = m_failedAt.contains(file)
                && m_failedAt.value(file).secsTo(now) < kRetryIntervalSecs;
        const bool stale = m_autoUpdate
                && m_state.value(file).checked.secsTo(now) >= kCheckIntervalSecs;
        if (force || (missing && !failedRecently) || (stale && !failedRecently))
            m_queue << file;
    }
    if (m_queue.isEmpty()) {
        scheduleCheck();
        return;
    }
    m_errors.clear();
    setBusy(true);
    fetchNext();
}

void GeoListUpdater::fetchNext()
{
    if (m_queue.isEmpty()) {
        setBusy(false);
        m_lastError = m_errors.join(QStringLiteral("; "));
        saveState();
        rebuild();
        scheduleCheck();
        emit statusChanged();
        return;
    }
    fetchChecksum(m_queue.takeFirst());
}

// The SHA-256 published beside a file, where there is one (runetfreedom's and
// v2fly's .dat files have it, as <file>.sha256sum): unchanged since the last
// download means nothing to download, and a download that does not match it
// is refused. A source without one is downloaded and read as it is.
void GeoListUpdater::fetchChecksum(const QString &fileUrl)
{
    QUrl url(fileUrl);
    url.setPath(url.path() + QStringLiteral(".sha256sum"));
    m_reply = m_nam->get(listRequest(url, m_appVersion));
    connect(m_reply, &QNetworkReply::downloadProgress, this, [this](qint64 received, qint64) {
        if (received > kMaxChecksumBytes && m_reply)
            m_reply->abort();
    });
    connect(m_reply, &QNetworkReply::finished, this, [this, fileUrl]() {
        QNetworkReply *reply = m_reply;
        m_reply = nullptr;
        reply->deleteLater();
        const QString expected = reply->error() == QNetworkReply::NoError ? checksumFrom(reply->readAll())
                                                                          : QString();
        const CachedFile cached = m_state.value(fileUrl);
        if (!expected.isEmpty() && expected == cached.sha256 && QFileInfo::exists(cachePath(fileUrl))) {
            m_state[fileUrl].checked = QDateTime::currentDateTimeUtc();
            finishFile(fileUrl, QString());
            return;
        }
        fetchFile(fileUrl, expected);
    });
}

void GeoListUpdater::fetchFile(const QString &fileUrl, const QString &expectedSha256)
{
    m_tooLarge = false;
    m_reply = m_nam->get(listRequest(QUrl(fileUrl), m_appVersion));
    connect(m_reply, &QNetworkReply::downloadProgress, this, [this](qint64 received, qint64 total) {
        if ((received > kMaxListBytes || total > kMaxListBytes) && m_reply) {
            m_tooLarge = true;
            m_reply->abort();
        }
    });
    connect(m_reply, &QNetworkReply::finished, this, [this, fileUrl, expectedSha256]() {
        QNetworkReply *reply = m_reply;
        m_reply = nullptr;
        reply->deleteLater();
        if (reply->error() != QNetworkReply::NoError) {
            finishFile(fileUrl, m_tooLarge ? QStringLiteral("the file is larger than %1 MB")
                                                     .arg(kMaxListBytes / (1024 * 1024))
                                           : replyError(reply));
            return;
        }
        const QByteArray data = reply->readAll();
        const QString sha = sha256Hex(data);
        if (!expectedSha256.isEmpty() && sha != expectedSha256) {
            finishFile(fileUrl, QStringLiteral("the download does not match its published SHA-256"));
            return;
        }

        // Read before it replaces the copy in the cache: an error page, a cut-off
        // file or one without the category asked for must not take the place of
        // a good list. In a worker, since a large .dat takes a moment.
        std::vector<SlotJob> jobs;
        for (const GeoListSlot &slot : geoListSlots()) {
            const GeoSource source = parseGeoSource(sourceFor(slot.id));
            if (source.valid() && source.file.toString(QUrl::FullyEncoded) == fileUrl)
                jobs.push_back({QString(), source, slot.kind, slot.id});
        }
        const QPointer<GeoListUpdater> self(this);
        const QString path = cachePath(fileUrl);
        QThreadPool::globalInstance()->start([self, jobs, data, sha, fileUrl, path]() {
            QString error;
            bool usable = false;
            for (const SlotJob &job : jobs) {
                std::vector<std::string> ignored;
                QString why;
                if (appendGeoEntries(data, job.source, job.kind, GeoDirection::ThroughVpn, &ignored,
                                     nullptr, &why))
                    usable = true;
                else if (error.isEmpty())
                    error = why;
            }
            bool saved = false;
            if (usable) {
                QDir().mkpath(QFileInfo(path).absolutePath());
                QSaveFile f(path);
                saved = f.open(QIODevice::WriteOnly) && f.write(data) == data.size() && f.commit();
                if (!saved)
                    error = QStringLiteral("the list could not be saved");
            }
            QMetaObject::invokeMethod(
                    self.data(),
                    [self, fileUrl, sha, saved, error]() {
                        if (!self)
                            return;
                        if (!saved) {
                            self->finishFile(fileUrl, error);
                            return;
                        }
                        const QDateTime now = QDateTime::currentDateTimeUtc();
                        CachedFile &c = self->m_state[fileUrl];
                        if (c.sha256 != sha)
                            c.fetched = now;
                        c.sha256 = sha;
                        c.checked = now;
                        self->finishFile(fileUrl, QString());
                    },
                    Qt::QueuedConnection);
        });
    });
}

void GeoListUpdater::finishFile(const QString &fileUrl, const QString &error)
{
    if (error.isEmpty()) {
        m_failedAt.remove(fileUrl);
    } else {
        m_failedAt.insert(fileUrl, QDateTime::currentDateTimeUtc());
        m_errors << QStringLiteral("%1: %2").arg(QUrl(fileUrl).fileName(), error);
    }
    fetchNext();
}

// The entries for the current mode, from the cached files, in a worker. A slot
// whose file is missing or cannot be read adds nothing; the others still count.
void GeoListUpdater::rebuild()
{
    const quint64 gen = ++m_rebuildGen;
    const GeoRoutingMode mode = m_mode;
    std::vector<SlotJob> jobs;
    if (mode != GeoRoutingMode::Off) {
        for (const GeoListSlot &slot : geoListSlots()) {
            if (slot.mode != mode)
                continue;
            const GeoSource source = parseGeoSource(sourceFor(slot.id));
            if (!source.valid())
                continue;
            jobs.push_back({cachePath(source.file.toString(QUrl::FullyEncoded)), source, slot.kind, slot.id});
        }
    }
    if (jobs.empty()) {
        const bool changed = !m_entries.isEmpty() || m_entriesMode != mode;
        m_entries.clear();
        m_entriesMode = mode;
        m_siteCount = 0;
        m_addressCount = 0;
        m_readError.clear();
        if (changed)
            emit entriesChanged();
        emit statusChanged();
        return;
    }
    const GeoDirection direction = geoDirectionFor(mode);
    const QPointer<GeoListUpdater> self(this);
    QThreadPool::globalInstance()->start([self, gen, mode, direction, jobs]() {
        std::vector<std::string> entries;
        qsizetype sites = 0;
        qsizetype addresses = 0;
        QStringList errors;
        for (const SlotJob &job : jobs) {
            QFile f(job.path);
            if (!f.open(QIODevice::ReadOnly))
                continue; // not downloaded yet: the update will say if it fails
            GeoListStats stats;
            QString why;
            if (appendGeoEntries(f.readAll(), job.source, job.kind, direction, &entries, &stats, &why))
                (job.kind == GeoListKind::Sites ? sites : addresses) += stats.entries;
            else
                errors << QStringLiteral("%1: %2").arg(job.id, why);
        }
        const std::string joined = joinGeoEntries(std::move(entries));
        QString text = QString::fromLatin1(joined.data(), qsizetype(joined.size()));
        QMetaObject::invokeMethod(
                self.data(),
                [self, gen, mode, text = std::move(text), sites, addresses, errors]() {
                    if (!self || gen != self->m_rebuildGen)
                        return;
                    const bool changed = text != self->m_entries || mode != self->m_entriesMode;
                    self->m_entries = text;
                    self->m_entriesMode = mode;
                    self->m_siteCount = sites;
                    self->m_addressCount = addresses;
                    self->m_readError = errors.join(QStringLiteral("; "));
                    if (changed)
                        emit self->entriesChanged();
                    emit self->statusChanged();
                },
                Qt::QueuedConnection);
    });
}

void GeoListUpdater::setBusy(bool busy)
{
    if (busy == m_busy)
        return;
    m_busy = busy;
    emit statusChanged();
}

} // namespace freetunnel
