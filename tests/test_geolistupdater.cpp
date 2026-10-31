// cppcheck-suppress-file missingIncludeSystem
// GeoListUpdater against a loopback HTTP server: the lists a mode needs are
// downloaded, kept, checked again without being downloaded again while their
// published SHA-256 says they are unchanged, and a bad download never takes the
// place of a good list.
#include <QtTest>

#include <QCryptographicHash>
#include <QSignalSpy>
#include <QTemporaryDir>

#include "core/GeoListUpdater.h"
#include "mock_http_server.h"

using namespace freetunnel;

namespace {

QByteArray varint(quint64 v)
{
    QByteArray out;
    do {
        char byte = char(v & 0x7f);
        v >>= 7;
        if (v)
            byte = char(byte | 0x80);
        out.append(byte);
    } while (v);
    return out;
}
QByteArray lenField(quint32 field, const QByteArray &body)
{
    return varint((quint64(field) << 3) | 2) + varint(quint64(body.size())) + body;
}
QByteArray intField(quint32 field, quint64 v) { return varint(quint64(field) << 3) + varint(v); }

// A geoip.dat with one category of IPv4 subnets.
QByteArray geoipDat(const QByteArray &code, const QList<QPair<QByteArray, int>> &subnets)
{
    QByteArray body = lenField(1, code);
    for (const auto &s : subnets)
        body += lenField(2, lenField(1, s.first) + intField(2, quint64(s.second)));
    return lenField(1, body);
}
QByteArray v4(int a, int b, int c, int d)
{
    QByteArray out;
    for (const int byte : {a, b, c, d})
        out.append(char(byte));
    return out;
}

QByteArray sha256sum(const QByteArray &data, const char *name)
{
    return QCryptographicHash::hash(data, QCryptographicHash::Sha256).toHex() + "  " + name + "\n";
}

MockHttpServer::Route route(const QByteArray &body, int status = 200)
{
    MockHttpServer::Route r;
    r.body = body;
    r.status = status;
    r.contentType = QByteArrayLiteral("application/octet-stream");
    return r;
}

} // namespace

class TestGeoListUpdater : public QObject {
    Q_OBJECT

private slots:
    void init();
    void cleanup();
    void theModesListsAreDownloadedAndBuilt();
    void aCachedListIsUsedWithoutTheNetwork();
    void anUnchangedListIsNotDownloadedAgain();
    void aDownloadThatDoesNotMatchItsChecksumIsRefused();
    void aBrokenDownloadKeepsTheGoodList();
    void offFetchesNothing();
    void theRussianListsLeaveOutWhatIsTooWideToBypass();

private:
    std::unique_ptr<GeoListUpdater> updater(GeoRoutingMode mode);
    QMap<QString, QString> sources() const;

    std::unique_ptr<QTemporaryDir> m_dir;
    std::unique_ptr<MockHttpServer> m_http;
    QString m_base; // kept for after m_http is gone
    QByteArray m_sites;
    QByteArray m_geoip;
};

void TestGeoListUpdater::init()
{
    m_dir = std::make_unique<QTemporaryDir>();
    QVERIFY(m_dir->isValid());
    m_http = std::make_unique<MockHttpServer>();
    QVERIFY(m_http->listen());
    m_base = m_http->baseUrl();
    m_sites = "domain:blocked.example\nfull:exact.example\n";
    m_geoip = geoipDat("RU-BLOCKED", {{v4(203, 0, 113, 0), 24}})
            + geoipDat("RU", {{v4(5, 3, 0, 0), 16}, {v4(0, 0, 0, 0), 1}});
    m_http->setRoute(QStringLiteral("/ru-blocked.txt"), route(m_sites));
    m_http->setRoute(QStringLiteral("/ru-blocked.txt.sha256sum"), route("Not Found", 404));
    m_http->setRoute(QStringLiteral("/geoip.dat"), route(m_geoip));
    m_http->setRoute(QStringLiteral("/geoip.dat.sha256sum"), route(sha256sum(m_geoip, "geoip.dat")));
    m_http->setRoute(QStringLiteral("/dlc.dat"), route("x", 404));
    m_http->setRoute(QStringLiteral("/dlc.dat.sha256sum"), route("x", 404));
}

void TestGeoListUpdater::cleanup()
{
    m_http.reset();
    m_dir.reset();
}

QMap<QString, QString> TestGeoListUpdater::sources() const
{
    const QString base = m_base;
    return {{QStringLiteral("blocked_sites"), base + QStringLiteral("/ru-blocked.txt")},
            {QStringLiteral("blocked_ips"), base + QStringLiteral("/geoip.dat#ru-blocked")},
            {QStringLiteral("russia_sites"), base + QStringLiteral("/ru-blocked.txt")},
            {QStringLiteral("russia_ips"), base + QStringLiteral("/geoip.dat#ru")}};
}

std::unique_ptr<GeoListUpdater> TestGeoListUpdater::updater(GeoRoutingMode mode)
{
    auto u = std::make_unique<GeoListUpdater>(m_dir->path(), QStringLiteral("test"));
    u->setSources(sources());
    u->setMode(mode);
    return u;
}

void TestGeoListUpdater::theModesListsAreDownloadedAndBuilt()
{
    const auto u = updater(GeoRoutingMode::Blocked);
    QTRY_VERIFY_WITH_TIMEOUT(u->ready() && !u->busy(), 10000);
    QCOMPARE(u->entriesMode(), GeoRoutingMode::Blocked);
    const QStringList entries = u->entries().split(QLatin1Char('\n'), Qt::SkipEmptyParts);
    QVERIFY(entries.contains(QStringLiteral("blocked.example")));
    QVERIFY(entries.contains(QStringLiteral("*.blocked.example")));
    QVERIFY(entries.contains(QStringLiteral("exact.example")));
    QVERIFY(entries.contains(QStringLiteral("203.0.113.0/24")));
    // Only the mode's own category of the shared file.
    QVERIFY(!entries.contains(QStringLiteral("5.3.0.0/16")));
    QCOMPARE(u->siteCount(), 2);
    QCOMPARE(u->addressCount(), 1);
    QVERIFY(u->lastChecked().isValid());
    QVERIFY2(u->lastError().isEmpty(), qPrintable(u->lastError()));
    QCOMPARE(m_http->requestCount(QStringLiteral("/geoip.dat")), 1);
}

void TestGeoListUpdater::aCachedListIsUsedWithoutTheNetwork()
{
    {
        const auto first = updater(GeoRoutingMode::Blocked);
        QTRY_VERIFY_WITH_TIMEOUT(first->ready() && !first->busy(), 10000);
    }
    m_http.reset(); // nothing to answer any more
    const auto second = updater(GeoRoutingMode::Blocked);
    QTRY_VERIFY_WITH_TIMEOUT(second->ready(), 10000);
    QVERIFY(second->entries().contains(QStringLiteral("203.0.113.0/24")));
    QVERIFY(!second->busy()); // checked within the day: nothing to fetch
}

void TestGeoListUpdater::anUnchangedListIsNotDownloadedAgain()
{
    const auto u = updater(GeoRoutingMode::Blocked);
    QTRY_VERIFY_WITH_TIMEOUT(u->ready() && !u->busy(), 10000);
    const QDateTime firstCheck = u->lastChecked();
    QTest::qWait(1100); // a later second for the check's time

    u->updateNow();
    QTRY_VERIFY_WITH_TIMEOUT(m_http->requestCount(QStringLiteral("/geoip.dat.sha256sum")) == 2, 10000);
    QTRY_VERIFY_WITH_TIMEOUT(!u->busy(), 10000);
    // Its published SHA-256 is the cached copy's, so the file is not fetched again.
    QCOMPARE(m_http->requestCount(QStringLiteral("/geoip.dat")), 1);
    // The text list has no SHA-256 beside it, so it is.
    QCOMPARE(m_http->requestCount(QStringLiteral("/ru-blocked.txt")), 2);
    QVERIFY(u->lastChecked() > firstCheck);
}

void TestGeoListUpdater::aDownloadThatDoesNotMatchItsChecksumIsRefused()
{
    const auto u = updater(GeoRoutingMode::Blocked);
    QTRY_VERIFY_WITH_TIMEOUT(u->ready() && !u->busy(), 10000);

    // A new edition announced, and a different file served.
    const QByteArray announced = geoipDat("RU-BLOCKED", {{v4(198, 51, 100, 0), 24}});
    m_http->setRoute(QStringLiteral("/geoip.dat.sha256sum"), route(sha256sum(announced, "geoip.dat")));
    m_http->setRoute(QStringLiteral("/geoip.dat"), route(geoipDat("RU-BLOCKED", {{v4(192, 0, 2, 0), 24}})));
    QSignalSpy status(u.get(), &GeoListUpdater::statusChanged);
    u->updateNow();
    QTRY_VERIFY_WITH_TIMEOUT(m_http->requestCount(QStringLiteral("/geoip.dat")) == 2, 10000);
    QTRY_VERIFY_WITH_TIMEOUT(!u->busy(), 10000);
    QVERIFY2(u->lastError().contains(QStringLiteral("SHA-256")), qPrintable(u->lastError()));
    QTRY_VERIFY_WITH_TIMEOUT(u->entries().contains(QStringLiteral("203.0.113.0/24")), 5000);
    QVERIFY(!u->entries().contains(QStringLiteral("192.0.2.0/24")));
}

void TestGeoListUpdater::aBrokenDownloadKeepsTheGoodList()
{
    const auto u = updater(GeoRoutingMode::Blocked);
    QTRY_VERIFY_WITH_TIMEOUT(u->ready() && !u->busy(), 10000);

    // An error page served with 200, as some proxies do.
    m_http->setRoute(QStringLiteral("/ru-blocked.txt"), route("<html>keyword:x</html>"));
    u->updateNow();
    QTRY_VERIFY_WITH_TIMEOUT(m_http->requestCount(QStringLiteral("/ru-blocked.txt")) == 2, 10000);
    QTRY_VERIFY_WITH_TIMEOUT(!u->busy(), 10000);
    QVERIFY(!u->lastError().isEmpty());
    QTRY_VERIFY_WITH_TIMEOUT(u->entries().contains(QStringLiteral("*.blocked.example")), 5000);

    // And a server that is gone keeps it too.
    m_http->setRoute(QStringLiteral("/ru-blocked.txt"), route("gone", 503));
    u->updateNow();
    QTRY_VERIFY_WITH_TIMEOUT(m_http->requestCount(QStringLiteral("/ru-blocked.txt")) == 3, 10000);
    QTRY_VERIFY_WITH_TIMEOUT(!u->busy(), 10000);
    QVERIFY(u->entries().contains(QStringLiteral("*.blocked.example")));
}

void TestGeoListUpdater::offFetchesNothing()
{
    const auto u = updater(GeoRoutingMode::Off);
    u->updateNow();
    QTest::qWait(300);
    QVERIFY(!u->ready());
    QVERIFY(!u->busy());
    QCOMPARE(m_http->requestCount(QStringLiteral("/geoip.dat")), 0);
    QCOMPARE(m_http->requestCount(QStringLiteral("/geoip.dat.sha256sum")), 0);
}

// The Russian lists take traffic out of the tunnel, so a subnet that would take
// half the internet with it is left out.
void TestGeoListUpdater::theRussianListsLeaveOutWhatIsTooWideToBypass()
{
    const auto u = updater(GeoRoutingMode::ExceptRussia);
    QTRY_VERIFY_WITH_TIMEOUT(u->ready() && !u->busy(), 10000);
    QCOMPARE(u->entriesMode(), GeoRoutingMode::ExceptRussia);
    QVERIFY(u->entries().contains(QStringLiteral("5.3.0.0/16")));
    QVERIFY(!u->entries().contains(QStringLiteral("0.0.0.0/1")));
    QVERIFY(!u->entries().contains(QStringLiteral("203.0.113.0/24")));
}

QTEST_MAIN(TestGeoListUpdater)
#include "test_geolistupdater.moc"
