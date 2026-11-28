// cppcheck-suppress-file missingIncludeSystem
// What a config's [listener.tun] lets go around the tunnel. Each case is a way
// traffic left the VPN while the window said Connected: excluded routes that add
// up to a whole address family, a config with no IPv6 route, and DNS that
// /etc/resolv.conf or systemd-resolved sent somewhere the tunnel does not carry.
#include <QtTest>

#include "core/ConfigToml.h"
#include "core/TunRouting.h"

using namespace freetunnel;

namespace {

// The excluded routes FreeTunnel writes into a config of its own.
QStringList defaultExcluded()
{
    return {QStringLiteral("0.0.0.0/8"),    QStringLiteral("10.0.0.0/8"),
            QStringLiteral("169.254.0.0/16"), QStringLiteral("172.16.0.0/12"),
            QStringLiteral("192.168.0.0/16"), QStringLiteral("224.0.0.0/3")};
}

QStringList defaultIncluded()
{
    return {QStringLiteral("0.0.0.0/0"), QStringLiteral("2000::/3")};
}

} // namespace

class TestTunRouting : public QObject {
    Q_OBJECT

private slots:
    void coverage_data();
    void coverage();
    void ipv6IsAddedOnlyToAFullIpv4Tunnel();
    void routedIntoTunnelHonoursExclusions();
    void resolvConfThroughTheStubIsResolvedsBusiness();
    void resolvConfToTheRouterGoesAroundTheTunnel();
    void resolvConfToAPublicServerGoesThroughIt();
    void resolvConfToALocalResolverIsReportedApart();
    void resolvConfWithNoNameserverIsLocal();
    void resolvedWithTheTunnelTakingEverything();
    void resolvedWithAnotherCatchAllLink();
    void resolvedWithoutTheTunnel();
    void readTunSectionReadsWhatTheCoreReads();
    void readTunSectionWithoutTheTable();
};

void TestTunRouting::coverage_data()
{
    QTest::addColumn<QStringList>("routes");
    QTest::addColumn<bool>("ipv4");
    QTest::addColumn<bool>("ipv6");
    QTest::newRow("FreeTunnel's own exclusions") << defaultExcluded() << false << false;
    QTest::newRow("halves of IPv4") << QStringList{"0.0.0.0/1", "128.0.0.0/1"} << true << false;
    QTest::newRow("halves out of order")
            << QStringList{"192.0.0.0/2", "0.0.0.0/1", "128.0.0.0/2"} << true << false;
    QTest::newRow("overlapping") << QStringList{"0.0.0.0/1", "64.0.0.0/2", "100.0.0.0/8", "128.0.0.0/1"}
                                 << true << false;
    QTest::newRow("built up from pieces")
            << QStringList{"0.0.0.0/1", "128.0.0.0/2", "192.0.0.0/3", "224.0.0.0/4", "240.0.0.0/5",
                           "248.0.0.0/6", "252.0.0.0/7", "254.0.0.0/8", "255.0.0.0/9", "255.128.0.0/9"}
            << true << false;
    QTest::newRow("the last /8 missing")
            << QStringList{"0.0.0.0/1", "128.0.0.0/2", "192.0.0.0/3", "224.0.0.0/4", "240.0.0.0/5",
                           "248.0.0.0/6", "252.0.0.0/7", "254.0.0.0/8"}
            << false << false;
    QTest::newRow("a gap at the start") << QStringList{"0.0.0.1/32", "0.0.0.2/31", "0.0.0.4/30"} << false
                                        << false;
    QTest::newRow("every address") << QStringList{"0.0.0.0/0", "::/0"} << true << true;
    QTest::newRow("halves of IPv6") << QStringList{"::/1", "8000::/1"} << false << true;
    QTest::newRow("global IPv6 alone") << QStringList{"2000::/3"} << false << false;
    QTest::newRow("junk is skipped") << QStringList{"example.com", "", "0.0.0.0/1", "128.0.0.0/1"} << true
                                     << false;
    QTest::newRow("bare addresses") << QStringList{"1.2.3.4", "2001:db8::1"} << false << false;
}

void TestTunRouting::coverage()
{
    QFETCH(QStringList, routes);
    QFETCH(bool, ipv4);
    QFETCH(bool, ipv6);
    QCOMPARE(coversAddressFamily(routes, false), ipv4);
    QCOMPARE(coversAddressFamily(routes, true), ipv6);
    QCOMPARE(coversEitherAddressFamily(routes), ipv4 || ipv6);
}

void TestTunRouting::ipv6IsAddedOnlyToAFullIpv4Tunnel()
{
    QCOMPARE(withIpv6Completed({"0.0.0.0/0"}), (QStringList{"0.0.0.0/0", "2000::/3"}));
    QCOMPARE(withIpv6Completed({"0.0.0.0/1", "128.0.0.0/1"}),
             (QStringList{"0.0.0.0/1", "128.0.0.0/1", "2000::/3"}));
    // Already has IPv6, of whatever extent: the config's own choice.
    QCOMPARE(withIpv6Completed(defaultIncluded()), defaultIncluded());
    QCOMPARE(withIpv6Completed({"0.0.0.0/0", "2001:db8::/32"}), (QStringList{"0.0.0.0/0", "2001:db8::/32"}));
    // Only part of IPv4: a tunnel to some networks, which IPv6 would not be part of.
    QCOMPARE(withIpv6Completed({"10.0.0.0/8"}), QStringList{"10.0.0.0/8"});
}

void TestTunRouting::routedIntoTunnelHonoursExclusions()
{
    const QStringList in = defaultIncluded();
    const QStringList out = defaultExcluded();
    QVERIFY(routedIntoTunnel(QHostAddress("1.1.1.1"), in, out));
    QVERIFY(routedIntoTunnel(QHostAddress("2606:4700:4700::1111"), in, out));
    QVERIFY(!routedIntoTunnel(QHostAddress("192.168.1.1"), in, out));
    QVERIFY(!routedIntoTunnel(QHostAddress("fe80::1%eth0"), in, out));
    QVERIFY(!routedIntoTunnel(QHostAddress("1.1.1.1"), {"10.0.0.0/8"}, {}));
}

void TestTunRouting::resolvConfThroughTheStubIsResolvedsBusiness()
{
    const ResolvConfCheck c = checkResolvConf(
            "# This is /run/systemd/resolve/stub-resolv.conf\nnameserver 127.0.0.53\noptions edns0 trust-ad\n"
            "search lan\n",
            defaultIncluded(), defaultExcluded());
    QVERIFY(c.viaResolved);
    QVERIFY(c.outsideTunnel.isEmpty());
    QVERIFY(c.localResolvers.isEmpty());
}

void TestTunRouting::resolvConfToTheRouterGoesAroundTheTunnel()
{
    // NetworkManager writing the router, as on a system without systemd-resolved;
    // and resolved's uplink file, which lists the real servers too.
    const ResolvConfCheck c = checkResolvConf("nameserver 192.168.1.1\nnameserver fe80::1%wlan0\n",
                                              defaultIncluded(), defaultExcluded());
    QVERIFY(!c.viaResolved);
    QCOMPARE(c.outsideTunnel, (QStringList{"192.168.1.1", "fe80::1%wlan0"}));
    // A route the user excluded in Settings counts as much as the config's own.
    const ResolvConfCheck cgnat = checkResolvConf("nameserver 100.64.0.1\n", defaultIncluded(),
                                                  defaultExcluded() << "100.64.0.0/10");
    QCOMPARE(cgnat.outsideTunnel, QStringList{"100.64.0.1"});
}

void TestTunRouting::resolvConfToAPublicServerGoesThroughIt()
{
    // Into the tunnel, where the core answers it.
    const ResolvConfCheck c =
            checkResolvConf("nameserver 1.1.1.1\nnameserver 2606:4700:4700::1111\n", defaultIncluded(),
                            defaultExcluded());
    QVERIFY(!c.viaResolved);
    QVERIFY(c.outsideTunnel.isEmpty());
    QVERIFY(c.localResolvers.isEmpty());
}

void TestTunRouting::resolvConfToALocalResolverIsReportedApart()
{
    const ResolvConfCheck c =
            checkResolvConf("nameserver 127.0.0.1\nnameserver 127.0.0.53\n", defaultIncluded(), defaultExcluded());
    QVERIFY(!c.viaResolved);
    QVERIFY(c.outsideTunnel.isEmpty());
    QCOMPARE(c.localResolvers, QStringList{"127.0.0.1"});
}

void TestTunRouting::resolvConfWithNoNameserverIsLocal()
{
    const ResolvConfCheck c = checkResolvConf("# empty\n", defaultIncluded(), defaultExcluded());
    QVERIFY(!c.viaResolved);
    QCOMPARE(c.localResolvers, QStringList{"127.0.0.1"});
}

void TestTunRouting::resolvedWithTheTunnelTakingEverything()
{
    const ResolvedDomainCheck c =
            checkResolvedDomains("Global:\nLink 2 (wlp3s0): lan\nLink 9 (tun0): ~.\n");
    QVERIFY(c.tunnelTakesAll);
    QVERIFY(c.otherLinks.isEmpty());
}

void TestTunRouting::resolvedWithAnotherCatchAllLink()
{
    const ResolvedDomainCheck c = checkResolvedDomains("Global:\nLink 2 (eth0): ~. lan\nLink 9 (tun1): ~.\n");
    QVERIFY(c.tunnelTakesAll);
    QCOMPARE(c.otherLinks, QStringList{"eth0"});
}

void TestTunRouting::resolvedWithoutTheTunnel()
{
    QVERIFY(!checkResolvedDomains("Global:\nLink 2 (eth0):\nLink 9 (tun0):\n").tunnelTakesAll);
    // "~.example" is a routing domain of its own, not the catch-all.
    QVERIFY(!checkResolvedDomains("Link 9 (tun0): ~.example\n").tunnelTakesAll);
    QVERIFY(!checkResolvedDomains(QString()).tunnelTakesAll);
}

void TestTunRouting::readTunSectionReadsWhatTheCoreReads()
{
    const TunSection t = readTunSection(
            "[endpoint]\nhostname = \"a\"\n\n[listener.tun]\nmtu_size = 1500\nchange_system_dns = false\n"
            "included_routes = [\n  \"0.0.0.0/1\",\n  \"128.0.0.0/1\", # halves\n]\n"
            "excluded_routes = ['10.0.0.0/8']\n");
    QVERIFY(t.present);
    QCOMPARE(t.includedRoutes, (QStringList{"0.0.0.0/1", "128.0.0.0/1"}));
    QCOMPARE(t.excludedRoutes, QStringList{"10.0.0.0/8"});
    QVERIFY(!t.changeSystemDns);
}

void TestTunRouting::readTunSectionWithoutTheTable()
{
    const TunSection t = readTunSection("[endpoint]\nhostname = \"a\"\n");
    QVERIFY(!t.present);
    QVERIFY(t.changeSystemDns);
    // What FreeTunnel writes for such a file is a full tunnel with DNS set.
    ConfigToml c;
    c.hostname = QStringLiteral("a");
    const TunSection built = readTunSection(buildConfigToml(c));
    QVERIFY(built.present);
    QVERIFY(built.changeSystemDns);
    QCOMPARE(built.includedRoutes, defaultIncluded());
    QCOMPARE(built.excludedRoutes, defaultExcluded());
}

QTEST_GUILESS_MAIN(TestTunRouting)
#include "test_tunrouting.moc"
