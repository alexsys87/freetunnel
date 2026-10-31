// cppcheck-suppress-file missingIncludeSystem
#pragma once

// Geo routing's lists: which ones each routing mode uses, where they come from,
// keeping them up to date, and turning them into the one list the VPN core is
// handed (core/GeoRules.h reads each file).
//
// Everything here runs in the GUI, as the user: the files are downloaded into
// the user's own cache, read there, and only the resulting entries travel to
// the elevated helper, as the user's own domain rules always have.

#include <QDateTime>
#include <QHash>
#include <QList>
#include <QMap>
#include <QObject>
#include <QSet>
#include <QString>
#include <QStringList>
#include <QTimer>

#include "core/GeoRules.h"

class QNetworkAccessManager;
class QNetworkReply;

namespace freetunnel {

// How traffic is split by geo lists.
//   Off           the user's own split-tunnelling rules alone, as before;
//   Blocked       only what is blocked in Russia goes through the tunnel;
//   ExceptRussia  everything goes through the tunnel but Russian sites.
enum class GeoRoutingMode { Off, Blocked, ExceptRussia };

QString geoRoutingModeName(GeoRoutingMode mode); // "off", "blocked", "except_ru"
GeoRoutingMode geoRoutingModeFromName(const QString &name); // anything unknown is Off

// Which way a mode's lists send what they name: Blocked's through the tunnel
// (the core's selective mode), ExceptRussia's around it (its general mode).
GeoDirection geoDirectionFor(GeoRoutingMode mode);

// One of the lists the modes use.
struct GeoListSlot {
    QString id;            // the settings key: blocked_sites, blocked_ips, russia_sites, russia_ips
    GeoListKind kind;      // how a .dat file at its source is read
    GeoRoutingMode mode;   // the mode that uses it
    QString defaultSource; // used while the user has not chosen another
};
const QList<GeoListSlot> &geoListSlots();
const GeoListSlot *geoListSlot(const QString &id);

// Downloads the lists the current mode uses, keeps them in a cache directory,
// checks them again once a day, and builds the core's entries from them in a
// worker thread. Idle while the mode is Off: nothing is fetched or read.
class GeoListUpdater : public QObject {
    Q_OBJECT
public:
    // How long a list is trusted before it is checked again, and how long after
    // a failed check the next one is tried.
    static constexpr qint64 kCheckIntervalSecs = 24 * 60 * 60;
    static constexpr qint64 kRetryIntervalSecs = 60 * 60;
    // A list file larger than this is refused: the largest published one, the
    // full geosite.dat, is about 75 MB.
    static constexpr qint64 kMaxListBytes = 128 * 1024 * 1024;

    GeoListUpdater(const QString &cacheDir, const QString &appVersion, QObject *parent = nullptr);
    ~GeoListUpdater() override;

    // The sources the user chose, by slot id; a missing or empty one means the
    // slot's default.
    void setSources(const QMap<QString, QString> &sources);
    QString sourceFor(const QString &slotId) const;
    void setMode(GeoRoutingMode mode);
    GeoRoutingMode mode() const { return m_mode; }
    // Whether lists are checked again by themselves once a day. Lists the mode
    // needs and that have never been downloaded are fetched either way.
    void setAutoUpdate(bool on);

    // Check every list the mode uses now, whatever their age.
    void updateNow();

    // The entries for the current mode (core/GeoRules.h's joinGeoEntries form),
    // or empty while there are none: before the first download, or when no list
    // of the mode could be read. ready() is whether there are.
    bool ready() const { return !m_entries.isEmpty(); }
    QString entries() const { return m_entries; }
    // The mode the entries were built for. Different from mode() for as long as a
    // switch is being read in, and the entries must not be used then.
    GeoRoutingMode entriesMode() const { return m_entriesMode; }

    // For the window.
    bool busy() const { return m_busy; }
    qsizetype siteCount() const { return m_siteCount; }
    qsizetype addressCount() const { return m_addressCount; }
    QDateTime lastChecked() const;      // the oldest successful check of the mode's lists
    // What went wrong in the last update, and in reading the lists since: empty
    // when nothing did.
    QString lastError() const;

signals:
    // The entries (or entriesMode) changed.
    void entriesChanged();
    // busy, the counts, lastChecked or lastError changed.
    void statusChanged();

private:
    struct CachedFile {
        QString sha256;
        QDateTime fetched; // when its contents last changed
        QDateTime checked; // when it was last confirmed current
    };

    QStringList neededFiles() const; // the source files of the mode's slots
    QString cachePath(const QString &fileUrl) const;
    void loadState();
    void saveState() const;
    void scheduleCheck();
    void startUpdate(bool force);
    void fetchNext();
    void fetchChecksum(const QString &fileUrl);
    void fetchFile(const QString &fileUrl, const QString &expectedSha256);
    void finishFile(const QString &fileUrl, const QString &error);
    void rebuild();
    void setBusy(bool busy);

    QString m_cacheDir;
    QString m_appVersion;
    QNetworkAccessManager *m_nam = nullptr;
    QTimer m_timer;
    QMap<QString, QString> m_sources;
    GeoRoutingMode m_mode = GeoRoutingMode::Off;
    bool m_autoUpdate = true;
    QHash<QString, CachedFile> m_state; // by file URL
    QHash<QString, QDateTime> m_failedAt; // by file URL, until a later success

    // The update in progress: the files still to check, one at a time.
    QStringList m_queue;
    QNetworkReply *m_reply = nullptr;
    bool m_tooLarge = false; // the reply was aborted for its size
    bool m_busy = false;
    QStringList m_errors;

    QString m_entries;
    GeoRoutingMode m_entriesMode = GeoRoutingMode::Off;
    qsizetype m_siteCount = 0;
    qsizetype m_addressCount = 0;
    QString m_lastError; // the last update's
    QString m_readError; // the last rebuild's
    quint64 m_rebuildGen = 0;
};

} // namespace freetunnel
