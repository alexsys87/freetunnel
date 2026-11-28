// cppcheck-suppress-file missingIncludeSystem
// The connect path of Backend: the toggle, starting a connect attempt, building
// its config off the GUI thread and handing that to the helper, and
// disconnecting. Split out of Backend.cpp, which had grown past the 500-line
// limit Codacy holds a file to.
#include "app/Backend.h"

#include <QFile>
#include <QProcess>
#include <QThread>
#include <QTimer>

#include <memory>

#include "core/ConfigToml.h"
#include "core/CredentialStore.h"
#include "core/TunRouting.h"

void Backend::toggle() {
    // A click while connecting cancels the attempt; while connected it
    // disconnects; otherwise it starts connecting.
    if (m_connected || m_connecting)
        disconnectVpn();
    else
        connectVpn();
}

bool Backend::shouldSkipConnectAttempt() const
{
    return m_connected || m_connecting || m_disconnecting;
}

void Backend::logConnectAttempt()
{
    QFile f(m_activePath);
    if (!f.open(QIODevice::ReadOnly | QIODevice::Text))
        return;
    const freetunnel::ConfigToml c = freetunnel::parseConfigToml(QString::fromUtf8(f.readAll()));
    const bool h3 = c.protocol == QLatin1String("http3");
    appendLog(QStringLiteral("INFO"),
              tr("Connecting to %1 [%2] · %3 over %4")
                      .arg(c.hostname.isEmpty() ? nameForPath(m_activePath) : c.hostname,
                           c.addresses,
                           h3 ? QStringLiteral("HTTP/3") : QStringLiteral("HTTP/2"),
                           h3 ? QStringLiteral("UDP/QUIC") : QStringLiteral("TCP")));
}

void Backend::failConnectNoPassword()
{
    m_connecting = false;
    // Nothing further will report Connected or Error for this attempt, so
    // clearReapplyingIfDone() would never run: a reapply that dies here used to
    // latch m_reapplying forever, masking m_disconnecting, swallowing every
    // vpnError toast and killing live rule reapply until some later connect
    // happened to succeed.
    m_reapplying = false;
    emit stateChanged();
    emit errorOccurred(tr("Config has no password — edit it and try again"));
    // Nor would anything apply a subscription's update held for the attempt.
    flushHeldSubscriptionUpdates();
}

void Backend::connectVpn() {
    if (m_activePath.isEmpty()) {
        emit errorOccurred(tr("Select a config first"));
        return;
    }
    // Connect hotkey / deep link: no-op when already up, while an attempt or a
    // config switch is in flight (a switch reads as connecting throughout), or
    // while disconnecting. The switch's own reconnect goes straight to
    // startConnectAttempt().
    if (shouldSkipConnectAttempt())
        return;
    startConnectAttempt();
}

void Backend::startConnectAttempt()
{
    // Show "Connecting…" immediately — the helper handshake (and any elevation
    // prompt) can take a few seconds before the core reports a real state.
    // A subsequent state change (Connected / Error / Disconnected) overrides it.
    if (!m_connecting) {
        m_connecting = true;
        emit stateChanged();
    }
    logConnectAttempt();
    buildConnectTomlAsync();
}

// Building the connect TOML reads the config's password out of the OS credential
// store. That is a blocking IPC to securityd on macOS, and while the system is
// asking the user whether this build of the app may read the item, it does not
// return — which froze the whole UI, spinner and all, for as long as the dialog
// was up. Do it on a worker thread and resume on ours.
void Backend::buildConnectTomlAsync()
{
    const quint64 generation = ++m_connectGen;
    m_awaitingToml = true;
    // This attempt reads the config as it is now, whatever a subscription wrote
    // into it before (rebuildForSubscriptionUpdate).
    m_subscriptionRebuildPending = false;
    const QString path = m_activePath;
    const QString level =
            m_settings.verbose_logs ? QStringLiteral("info") : QStringLiteral("warn");
    // NOT parented to this Backend, and that is the fix for an abort rather than a
    // style preference. A QThread that is a child of Backend is deleted by
    // ~QObject, which on a thread still inside the keychain read means QThread's
    // destructor reaching qFatal("Destroyed while thread is still running") and
    // the process dying on SIGABRT. The window is exactly the one this function
    // exists for: the read blocks while macOS asks the user whether this build may
    // open the item, and quitting while that prompt is up is an ordinary thing to
    // do. Unparented, nothing destroys it underneath itself; it deletes itself on
    // finished() below.
    auto *watcher = new QThread;
    // Carried out of the worker by value rather than written into a member: the
    // worker thread must touch no part of Backend, or "nothing destroys it
    // underneath itself" stops being true in the other direction.
    auto toml = std::make_shared<QString>();
    auto builtOn = std::make_shared<QThread *>(nullptr);
    // Qt::DirectConnection is what makes this actually asynchronous, and it is not
    // decoration. A QThread OBJECT lives in the thread that created it — here the
    // GUI thread — so an auto connection to one of its own signals is queued back
    // to the GUI thread and the body runs there. This function exists to keep the
    // blocking keychain read off the UI, and without this it did the opposite:
    // spun up a thread, then froze the window on it anyway. Direct delivery runs
    // the body in the emitting thread, which for started() is the worker.
    QObject::connect(
            watcher, &QThread::started, watcher,
            [watcher, toml, builtOn, path, level]() {
                *builtOn = QThread::currentThread();
                *toml = freetunnel::buildConnectConfigToml(path, level);
                watcher->quit();
            },
            Qt::DirectConnection);
    // The result hop is a real connection with `this` as receiver, not an
    // invokeMethod on a pointer the worker holds. That matters for two reasons.
    // Qt maintains connections under its own mutex and removes this one when
    // Backend is destroyed, so there is no window in which the worker is about to
    // post to an object that has just gone; a QPointer checked on the worker
    // thread cannot promise that, because it is not thread-safe and the object can
    // die between the check and the use. And `this` lives in the GUI thread, so
    // the default connection type delivers queued — finished() is emitted in the
    // worker, the body runs on ours.
    QObject::connect(watcher, &QThread::finished, this, [this, generation, toml, builtOn]() {
#ifdef FT_ENABLE_TEST_HOOKS
        m_lastTomlBuildThread.store(*builtOn);
#endif
        onConnectTomlReady(generation, *toml);
    });
    QObject::connect(watcher, &QThread::finished, watcher, &QObject::deleteLater);
    watcher->start();
}

void Backend::onConnectTomlReady(quint64 generation, const QString &toml)
{
    // Superseded by a disconnect, a config switch or a newer attempt while the
    // credential store had us waiting.
    if (generation != m_connectGen)
        return;
    m_awaitingToml = false;
    if (toml.isEmpty()) {
        failConnectNoPassword();
        return;
    }
    // From here a switch is an ordinary connect attempt: what the helper reports
    // is the new session, not the old one going down. Holding the guard until
    // Connected swallowed every error of a switch to a server that fails, left
    // "Connecting…" up for as long as the core kept retrying, and made the next
    // config pick a silent no-op.
    m_reapplying = false;
    m_inConnect = true;
    m_client.loadConfigFromToml(toml);
    applySplitRules(); // push domain-bypass rules to the core before connecting
    m_client.setKillSwitch(m_settings.killswitch_enabled);
    m_client.setKillSwitchPortsFromConfig(m_settings.killswitch_ports_from_config);
    m_client.setLogLevel(m_settings.verbose_logs ? QStringLiteral("info") : QStringLiteral("warn"));
    m_client.setSessionLogging(m_settings.logging_enabled);
    m_client.connectVpn();
    m_inConnect = false;
}

void Backend::disconnectVpn() {
    // A disconnect wins over a connect-on-startup that has not started yet.
    // "Disconnect" handed over with the launch, by a script or a Stream Deck,
    // found nothing up to take down, and the auto-connect brought the tunnel up
    // 600 ms later all the same.
    m_autoConnectTimer.stop();
    if (!m_connected && !m_connecting)
        return; // nothing to disconnect or cancel
    ++m_connectGen; // a credential read still in flight must not start a session
    m_awaitingToml = false;
    m_reapplying = false;
    m_pendingReconnect = false; // an explicit disconnect cancels a config-switch reconnect
    // Show "Disconnecting…" right away; clear the optimistic "Connecting…".
    m_connecting = false;
    m_disconnecting = true;
    emit stateChanged();
    m_client.disconnectVpn(); // aborts a pending startup, or stops a live tunnel
}

// On Linux the VPN core points the system at the tunnel's DNS through
// systemd-resolved alone (`resolvectl dns` and `resolvectl domain <tun> ~.`). A
// machine whose /etc/resolv.conf sends queries elsewhere, say to the router that
// NetworkManager wrote there, keeps doing so, and the router is on the local
// network, which the tunnel excludes: every lookup went to the provider while the
// window said Connected. The core only logs that it could not set DNS. Checked
// once per connect, from the files and commands any user may read.
void Backend::checkSystemDnsAfterConnect()
{
#if defined(Q_OS_LINUX) && !defined(Q_OS_ANDROID)
    if (m_dnsCheckedGen == m_connectGen)
        return;
    m_dnsCheckedGen = m_connectGen;
    QFile config(m_activePath);
    if (!config.open(QIODevice::ReadOnly | QIODevice::Text))
        return;
    // Through buildConfigToml(), so a file without [listener.tun] is read with the
    // routing FreeTunnel gives it.
    const freetunnel::TunSection tun = freetunnel::readTunSection(
            freetunnel::buildConfigToml(freetunnel::parseConfigToml(QString::fromUtf8(config.readAll()))));
    if (!tun.changeSystemDns) {
        appendLog(QStringLiteral("WARN"),
                  tr("The config does not set the system DNS: DNS queries may go around the VPN."));
        return;
    }
    QString resolvConfPath = QStringLiteral("/etc/resolv.conf");
#ifdef FT_ENABLE_TEST_HOOKS
    // Test builds look only at a file a test names: the machine running them has
    // a resolv.conf of its own, which no test is about.
    resolvConfPath = qEnvironmentVariable("FT_TEST_RESOLV_CONF");
    if (resolvConfPath.isEmpty())
        return;
#endif
    QFile resolvConf(resolvConfPath);
    if (!resolvConf.open(QIODevice::ReadOnly | QIODevice::Text))
        return;
    const QStringList included = freetunnel::withIpv6Completed(tun.includedRoutes);
    const QStringList excluded = tun.excludedRoutes + m_settings.excluded_routes;
    const freetunnel::ResolvConfCheck check =
            freetunnel::checkResolvConf(QString::fromUtf8(resolvConf.readAll()), included, excluded);
    if (!check.outsideTunnel.isEmpty()) {
        const QString text = tr("DNS queries go around the VPN: /etc/resolv.conf sends them to %1, outside "
                                "the tunnel. Use systemd-resolved, or set a DNS server that the VPN carries.")
                                     .arg(check.outsideTunnel.join(QStringLiteral(", ")));
        appendLog(QStringLiteral("WARN"), text);
        emit errorOccurred(text);
    }
    if (!check.localResolvers.isEmpty()) {
        appendLog(QStringLiteral("WARN"),
                  tr("DNS goes to a resolver on this computer (%1). FreeTunnel cannot point it at the VPN: "
                     "if it forwards to your router or provider, DNS queries go around the VPN.")
                          .arg(check.localResolvers.join(QStringLiteral(", "))));
    }
    if (check.viaResolved)
        checkResolvedLinks();
#endif
}

// systemd-resolved is in use: it sends every query to the tunnel only if the
// core's tun interface carries the "~." routing domain, and no other link does.
void Backend::checkResolvedLinks()
{
#if defined(Q_OS_LINUX) && !defined(Q_OS_ANDROID)
    auto *proc = new QProcess(this);
    const quint64 generation = m_connectGen;
    auto report = [this, proc, generation](bool ran) {
        const QString output = ran ? QString::fromUtf8(proc->readAllStandardOutput()) : QString();
        proc->deleteLater();
        // A disconnect or another connect since: this answer is not about it.
        if (generation != m_connectGen || !m_connected)
            return;
        const freetunnel::ResolvedDomainCheck links = freetunnel::checkResolvedDomains(output);
        QString text;
        if (!links.tunnelTakesAll)
            text = tr("systemd-resolved did not take the VPN's DNS settings: DNS queries may go around "
                      "the VPN.");
        else if (!links.otherLinks.isEmpty())
            text = tr("systemd-resolved also sends every DNS query to %1, around the VPN.")
                           .arg(links.otherLinks.join(QStringLiteral(", ")));
        if (text.isEmpty())
            return;
        appendLog(QStringLiteral("WARN"), text);
        emit errorOccurred(text);
    };
    connect(proc, &QProcess::finished, this,
            [proc, report](int code, QProcess::ExitStatus status) {
                report(status == QProcess::NormalExit && code == 0);
            });
    connect(proc, &QProcess::errorOccurred, this, [proc, report](QProcess::ProcessError error) {
        // FailedToStart: no resolvectl, and finished() never comes. A crash or
        // a timeout's kill is reported by finished() as well.
        if (error == QProcess::FailedToStart)
            report(false);
        else if (error == QProcess::Timedout)
            proc->kill();
    });
    QTimer::singleShot(5000, proc, [proc]() {
        if (proc->state() != QProcess::NotRunning)
            proc->kill();
    });
    QString program = QStringLiteral("resolvectl");
#ifdef FT_ENABLE_TEST_HOOKS
    if (qEnvironmentVariableIsSet("FT_TEST_RESOLVECTL"))
        program = qEnvironmentVariable("FT_TEST_RESOLVECTL");
#endif
    proc->start(program, {QStringLiteral("domain")});
#endif
}
