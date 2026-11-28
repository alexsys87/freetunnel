// cppcheck-suppress-file missingIncludeSystem
#include "core/TunRouting.h"

#include <QRegularExpression>

#include <algorithm>
#include <optional>
#include <vector>

namespace freetunnel {

namespace {

// An address as an unsigned number of up to 128 bits, IPv4 in the low 32.
struct U128 {
    quint64 hi = 0;
    quint64 lo = 0;

    bool operator<(const U128 &o) const { return hi != o.hi ? hi < o.hi : lo < o.lo; }
    bool operator==(const U128 &o) const { return hi == o.hi && lo == o.lo; }
};

// All ones in the low `bits` bits (bits in 0..128).
U128 lowOnes(int bits)
{
    U128 v;
    if (bits >= 128) {
        v.hi = ~quint64(0);
        v.lo = ~quint64(0);
    } else if (bits >= 64) {
        v.lo = ~quint64(0);
        v.hi = bits == 64 ? 0 : (~quint64(0) >> (128 - bits));
    } else if (bits > 0) {
        v.lo = ~quint64(0) >> (64 - bits);
    }
    return v;
}

U128 operator|(const U128 &a, const U128 &b) { return {a.hi | b.hi, a.lo | b.lo}; }
U128 operator&(const U128 &a, const U128 &b) { return {a.hi & b.hi, a.lo & b.lo}; }
U128 operator~(const U128 &a) { return {~a.hi, ~a.lo}; }

U128 plusOne(const U128 &a)
{
    U128 v = a;
    if (++v.lo == 0)
        ++v.hi;
    return v;
}

U128 toNumber(const QHostAddress &address)
{
    U128 v;
    if (address.protocol() == QAbstractSocket::IPv4Protocol) {
        v.lo = address.toIPv4Address();
        return v;
    }
    const Q_IPV6ADDR bytes = address.toIPv6Address();
    for (int i = 0; i < 8; ++i)
        v.hi = (v.hi << 8) | bytes[i];
    for (int i = 8; i < 16; ++i)
        v.lo = (v.lo << 8) | bytes[i];
    return v;
}

QHostAddress withoutScope(QHostAddress address)
{
    address.setScopeId(QString());
    return address;
}

// A route as the core reads it: a subnet, or an address standing for itself.
std::optional<QPair<QHostAddress, int>> parseRoute(const QString &raw)
{
    const QString route = raw.trimmed();
    if (route.isEmpty())
        return std::nullopt;
    if (!route.contains(QLatin1Char('/'))) {
        const QHostAddress address(route);
        if (address.isNull())
            return std::nullopt;
        const bool v4 = address.protocol() == QAbstractSocket::IPv4Protocol;
        return QPair<QHostAddress, int>(withoutScope(address), v4 ? 32 : 128);
    }
    const QPair<QHostAddress, int> subnet = QHostAddress::parseSubnet(route);
    if (subnet.first.isNull() || subnet.second < 0)
        return std::nullopt;
    return subnet;
}

bool isIpv6(const QHostAddress &address)
{
    return address.protocol() == QAbstractSocket::IPv6Protocol;
}

bool inAnyRoute(const QHostAddress &address, const QStringList &routes)
{
    for (const QString &raw : routes) {
        const auto route = parseRoute(raw);
        if (route && address.isInSubnet(route->first, route->second))
            return true;
    }
    return false;
}

} // namespace

bool coversAddressFamily(const QStringList &routes, bool ipv6)
{
    const int width = ipv6 ? 128 : 32;
    std::vector<QPair<U128, U128>> intervals;
    for (const QString &raw : routes) {
        const auto route = parseRoute(raw);
        if (!route || isIpv6(route->first) != ipv6)
            continue;
        const U128 hostBits = lowOnes(width - route->second);
        const U128 first = toNumber(route->first) & ~hostBits;
        intervals.push_back({first, first | hostBits});
    }
    std::sort(intervals.begin(), intervals.end(),
              [](const auto &a, const auto &b) { return a.first < b.first; });
    const U128 last = lowOnes(width);
    U128 next; // the lowest address not yet covered
    for (const auto &[first, end] : intervals) {
        if (next < first)
            return false; // a gap
        if (end == last)
            return true;
        if (!(end < next))
            next = plusOne(end);
    }
    return false;
}

bool coversEitherAddressFamily(const QStringList &routes)
{
    return coversAddressFamily(routes, false) || coversAddressFamily(routes, true);
}

bool hasIpv6Route(const QStringList &routes)
{
    return std::any_of(routes.cbegin(), routes.cend(), [](const QString &raw) {
        const auto route = parseRoute(raw);
        return route && isIpv6(route->first);
    });
}

bool routedIntoTunnel(const QHostAddress &address, const QStringList &included,
                      const QStringList &excluded)
{
    const QHostAddress plain = withoutScope(address);
    return inAnyRoute(plain, included) && !inAnyRoute(plain, excluded);
}

QString globalIpv6Route()
{
    return QStringLiteral("2000::/3");
}

QStringList withIpv6Completed(const QStringList &included)
{
    if (!coversAddressFamily(included, false) || hasIpv6Route(included))
        return included;
    QStringList out = included;
    out << globalIpv6Route();
    return out;
}

ResolvConfCheck checkResolvConf(const QString &resolvConf, const QStringList &included,
                                const QStringList &excluded)
{
    static const QRegularExpression nameserver(
            QStringLiteral("^\\s*nameserver\\s+(\\S+)"), QRegularExpression::MultilineOption);
    const QHostAddress stub(QStringLiteral("127.0.0.53"));
    const QHostAddress stubProxy(QStringLiteral("127.0.0.54"));
    ResolvConfCheck out;
    bool anyServer = false;
    bool allStub = true;
    auto it = nameserver.globalMatch(resolvConf);
    while (it.hasNext()) {
        const QString text = it.next().captured(1);
        const QHostAddress address(text);
        if (address.isNull())
            continue;
        anyServer = true;
        const QHostAddress plain = withoutScope(address);
        if (plain == stub || plain == stubProxy)
            continue;
        allStub = false;
        if (plain.isLoopback())
            out.localResolvers << text;
        else if (!routedIntoTunnel(plain, included, excluded))
            out.outsideTunnel << text;
    }
    // glibc falls back to 127.0.0.1 when the file names no nameserver: a local
    // resolver, if one listens there.
    if (!anyServer) {
        allStub = false;
        out.localResolvers << QStringLiteral("127.0.0.1");
    }
    out.viaResolved = allStub;
    return out;
}

ResolvedDomainCheck checkResolvedDomains(const QString &resolvectlDomainOutput)
{
    // "Link 7 (tun0): ~. example.com", one line per link.
    static const QRegularExpression link(QStringLiteral("^Link\\s+\\d+\\s+\\(([^)]+)\\):(.*)$"),
                                         QRegularExpression::MultilineOption);
    static const QRegularExpression catchAll(QStringLiteral("(^|\\s)~\\.(\\s|$)"));
    static const QRegularExpression tunName(QStringLiteral("^tun\\d+$"));
    ResolvedDomainCheck out;
    auto it = link.globalMatch(resolvectlDomainOutput);
    while (it.hasNext()) {
        const auto m = it.next();
        if (!catchAll.match(m.captured(2)).hasMatch())
            continue;
        const QString name = m.captured(1).trimmed();
        if (tunName.match(name).hasMatch())
            out.tunnelTakesAll = true;
        else
            out.otherLinks << name;
    }
    return out;
}

} // namespace freetunnel
