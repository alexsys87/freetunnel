#pragma once

// What a config's [listener.tun] sends into the tunnel, and whether anything a
// desktop OS does on its own would take traffic around it. Shared by the GUI
// (import and the DNS check after a connect) and the helper (the routes a
// session is built with).

#include <QHostAddress>
#include <QString>
#include <QStringList>

namespace freetunnel {

// Whether the subnets among `routes`, taken together, are every IPv4 address
// (`ipv6` false) or every IPv6 address (`ipv6` true). Entries that are not a
// subnet or an address are skipped, as the core skips them. A bare address is a
// /32 or /128.
bool coversAddressFamily(const QStringList &routes, bool ipv6);

// Either family: a list of excluded routes this is true of takes all of that
// family's traffic out of the tunnel.
bool coversEitherAddressFamily(const QStringList &routes);

// Whether any entry is an IPv6 subnet or address.
bool hasIpv6Route(const QStringList &routes);

// Whether a packet to `address` goes into the tunnel: inside an included route
// and outside every excluded one, as the core installs them.
bool routedIntoTunnel(const QHostAddress &address, const QStringList &included,
                      const QStringList &excluded);

// The global IPv6 unicast range FreeTunnel's own configs send into the tunnel.
QString globalIpv6Route();

// The included routes a session is built with. A config that sends every IPv4
// address into the tunnel and names no IPv6 route at all left IPv6 to the open
// network: every site that has an AAAA record went around the VPN. Such a list
// gets globalIpv6Route() added, as FreeTunnel writes it itself; any other list
// is returned unchanged, since routing only part of IPv4 is a choice the config
// made on purpose.
QStringList withIpv6Completed(const QStringList &included);

// What /etc/resolv.conf sends DNS queries to, measured against the tunnel.
struct ResolvConfCheck {
    // Every nameserver is systemd-resolved's stub (127.0.0.53 or 127.0.0.54),
    // so where queries go is decided by resolved's per-link settings.
    bool viaResolved = false;
    // Nameservers a query reaches without entering the tunnel: the router, the
    // provider's resolver, a link-local address.
    QStringList outsideTunnel;
    // Other resolvers on this machine (dnsmasq, unbound...). Where they forward
    // is their own setting, which FreeTunnel cannot see.
    QStringList localResolvers;
};

ResolvConfCheck checkResolvConf(const QString &resolvConf, const QStringList &included,
                                const QStringList &excluded);

// What `resolvectl domain` says about a tunnel interface the core configured.
struct ResolvedDomainCheck {
    // A tun interface carries the "~." routing domain: every query goes to it.
    bool tunnelTakesAll = false;
    // Other links that carry "~." as well, which resolved queries alongside.
    QStringList otherLinks;
};

ResolvedDomainCheck checkResolvedDomains(const QString &resolvectlDomainOutput);

} // namespace freetunnel
