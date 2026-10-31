// cppcheck-suppress-file missingIncludeSystem
// The Windows window chrome (src/app/WindowsChrome.cpp: the QWindowKit agent, the
// title bar, the system buttons, the dark-mode follower, the minimum size and the
// show), on the Windows platform plugin — the only place it runs. Every other test
// job runs offscreen, under which the app never sets it up (its window id there is
// a counter, not an HWND), so the window most users see had never been put
// together by any test, and nobody working on this can look at it by hand.
//
// The window is a stand-in for Main.qml with the same object names, laid out the
// way Main.qml lays them out. What is asked of it is what Windows itself asks: the
// answer to WM_NCHITTEST at a point, which is what decides whether a press there
// drags the window, maximises it, or reaches the app — and, over the maximise
// button, whether Windows 11 offers its Snap Layouts.
#include <QtTest>

#include <QGuiApplication>
#include <QQmlComponent>
#include <QQmlEngine>
#include <QQuickItem>
#include <QQuickWindow>

#include <QWKQuick/quickwindowagent.h>

#include <windows.h>

#include <memory>

#include "app/WindowsChrome.h"

namespace {

// Main.qml with windowsAgent set, as far as setupWindowsChrome can tell: created
// hidden and without a minimum size, a title band across the top holding the nav
// tiles and, at its right end, the three buttons.
const char kWindowQml[] = R"(
import QtQuick
import QtQuick.Window
Window {
    width: 640; height: 480
    visible: false
    flags: Qt.Window
    property bool darkChrome: false
    Item {
        objectName: "titleBar"
        width: parent.width; height: 40
    }
    Item { objectName: "navRow"; x: 100; width: 200; height: 40 }
    Rectangle { objectName: "windowMinButton"; x: parent.width - 138; width: 46; height: 40 }
    Rectangle { objectName: "windowMaxButton"; x: parent.width - 92; width: 46; height: 40 }
    Rectangle { objectName: "windowCloseRect"; x: parent.width - 46; width: 46; height: 40 }
}
)";

// The same window with a tray icon, as Main.qml creates one: setupWindowsChrome
// reads its "available" property to decide whether a hidden window has anywhere
// to be brought back from. `trayAvailable` is the property, `withTray` whether the
// item exists at all — a desktop with no tray has neither.
QByteArray hiddenWindowQml(bool withTray, bool trayAvailable)
{
    QByteArray qml(kWindowQml);
    qml.chop(2); // drop the trailing "}\n"
    if (withTray) {
        qml += QByteArray("    QtObject { objectName: \"systemTray\"; property bool available: ")
               + (trayAvailable ? "true" : "false") + " }\n";
    }
    qml += "}\n";
    return qml;
}

QString hitName(LRESULT hit)
{
    switch (hit) {
    case HTCLIENT: return QStringLiteral("HTCLIENT");
    case HTCAPTION: return QStringLiteral("HTCAPTION");
    case HTMINBUTTON: return QStringLiteral("HTMINBUTTON");
    case HTMAXBUTTON: return QStringLiteral("HTMAXBUTTON");
    case HTCLOSE: return QStringLiteral("HTCLOSE");
    case HTTOP: return QStringLiteral("HTTOP");
    default: return QString::number(hit);
    }
}

} // namespace

class TestWindowsChrome : public QObject {
    Q_OBJECT

private slots:
    void initTestCase();
    void cleanupTestCase();
    void theAgentTakesTheWindowAndShowsIt();
    void aWindowAskedToStartMinimizedStaysHidden();
    void startMinimizedIsIgnoredWithoutATray();
    void eachButtonAnswersAsTheButtonItIs();
    void theTitleBandDragsOnlyWhileItIsEnabled();
    void followingTheDarkSwitchDoesNotUndoTheChrome();

private:
    QQuickItem *item(const char *name) const
    {
        return m_window->findChild<QQuickItem *>(QLatin1String(name));
    }
    // What Windows hears at the centre of `name`, offset by (dx, dy) logical pixels.
    LRESULT hitTestAt(const char *name, qreal dx = 0, qreal dy = 0) const;

    QQmlEngine m_engine;
    QQuickWindow *m_window = nullptr;
};

LRESULT TestWindowsChrome::hitTestAt(const char *name, qreal dx, qreal dy) const
{
    const QQuickItem *target = item(name);
    if (!target)
        return HTNOWHERE;
    const QPointF centre = target->mapToScene(QPointF(target->width() / 2 + dx, target->height() / 2 + dy));
    const qreal dpr = m_window->devicePixelRatio();
    // Client coordinates, which the agent makes the whole window: it has no frame.
    POINT p{LONG(centre.x() * dpr), LONG(centre.y() * dpr)};
    const auto hwnd = reinterpret_cast<HWND>(m_window->winId());
    ::ClientToScreen(hwnd, &p);
    return ::SendMessageW(hwnd, WM_NCHITTEST, 0, MAKELPARAM(p.x, p.y));
}

void TestWindowsChrome::initTestCase()
{
    // Set for this test alone by tests/CMakeLists.txt, over the offscreen the rest
    // of the suite runs on: that is the platform the app skips this setup on.
    QCOMPARE(QGuiApplication::platformName(), QStringLiteral("windows"));
    QQmlComponent component(&m_engine);
    component.setData(kWindowQml, QUrl());
    m_window = qobject_cast<QQuickWindow *>(component.create());
    QVERIFY2(m_window, qPrintable(component.errorString()));
    freetunnel::setupWindowsChrome(m_window);
    QVERIFY(QTest::qWaitForWindowExposed(m_window));
}

void TestWindowsChrome::cleanupTestCase()
{
    delete m_window;
}

void TestWindowsChrome::theAgentTakesTheWindowAndShowsIt()
{
    QVERIFY(m_window->findChild<QWK::QuickWindowAgent *>());
    QVERIFY(m_window->isVisible());
    // Applied after the agent, which changes how Qt sizes the window: applied
    // before, it came out wrong (QWindowKit issue #76).
    QCOMPARE(m_window->minimumSize(), QSize(400, 460));
    // No frame: the agent hands the whole window to the app as client area.
    const auto hwnd = reinterpret_cast<HWND>(m_window->winId());
    RECT client{};
    RECT outer{};
    QVERIFY(::GetClientRect(hwnd, &client));
    QVERIFY(::GetWindowRect(hwnd, &outer));
    QCOMPARE(client.right - client.left, outer.right - outer.left);
}

// "Start minimized": with a tray to hold it, the window is set up but left
// unshown — a launch at logon then leaves no window on screen. What brings it
// back is the tray's own Show, which calls show() as usual; the window is only
// never shown here.
void TestWindowsChrome::aWindowAskedToStartMinimizedStaysHidden()
{
    QQmlComponent component(&m_engine);
    component.setData(hiddenWindowQml(/*withTray=*/true, /*trayAvailable=*/true), QUrl());
    std::unique_ptr<QQuickWindow> window(qobject_cast<QQuickWindow *>(component.create()));
    QVERIFY2(window, qPrintable(component.errorString()));
    freetunnel::setupWindowsChrome(window.get(), /*startHidden=*/true);
    // The agent is on it either way: it is what makes the window a Windows window.
    QVERIFY(window->findChild<QWK::QuickWindowAgent *>());
    QCOMPARE(window->minimumSize(), QSize(400, 460));
    QVERIFY2(!window->isVisible(), "a window with a tray to go to must start hidden");
    // And it still shows on demand, which is what the tray's Show does.
    window->show();
    QVERIFY(QTest::qWaitForWindowExposed(window.get()));
}

// A hidden window nothing can bring back is worse than one that opened: with no
// tray — or one that reports itself unavailable, as offscreen does — "start
// minimized" is ignored and the window is shown, as it always was.
void TestWindowsChrome::startMinimizedIsIgnoredWithoutATray()
{
    for (const bool trayAvailable : {false, true}) {
        for (const bool withTray : {false, true}) {
            if (withTray && trayAvailable)
                continue; // the case above
            QQmlComponent component(&m_engine);
            component.setData(hiddenWindowQml(withTray, trayAvailable), QUrl());
            std::unique_ptr<QQuickWindow> window(qobject_cast<QQuickWindow *>(component.create()));
            QVERIFY2(window, qPrintable(component.errorString()));
            freetunnel::setupWindowsChrome(window.get(), /*startHidden=*/true);
            QVERIFY2(QTest::qWaitForWindowExposed(window.get()),
                     "no tray to hide to: the window has to be shown");
        }
    }
}

// Registered as the system's own buttons, they answer as those buttons do. Over
// maximise that is HTMAXBUTTON, the whole of what Windows 11 needs to show Snap
// Layouts on hover.
void TestWindowsChrome::eachButtonAnswersAsTheButtonItIs()
{
    // Not "min" and "max": <windows.h> has macros by those names.
    const LRESULT minimise = hitTestAt("windowMinButton");
    const LRESULT maximise = hitTestAt("windowMaxButton");
    const LRESULT closeHit = hitTestAt("windowCloseRect");
    QVERIFY2(minimise == HTMINBUTTON, qPrintable(hitName(minimise)));
    QVERIFY2(maximise == HTMAXBUTTON, qPrintable(hitName(maximise)));
    QVERIFY2(closeHit == HTCLOSE, qPrintable(hitName(closeHit)));
}

// The band drags the window, except where the nav tiles reach into it. And it is
// switched off while anything covers it (Main.qml binds the title bar's enabled
// to that): the agent decides what is title bar by geometry alone, so if it took
// no notice of `enabled`, an overlay's backdrop over the band would become a drag
// handle and the click meant to close the overlay would move the window instead.
void TestWindowsChrome::theTitleBandDragsOnlyWhileItIsEnabled()
{
    // Left of the nav tiles, and clear of the resize band along the top edge.
    const qreal leftOfNav = -(item("titleBar")->width() / 2) + 40;
    const LRESULT band = hitTestAt("titleBar", leftOfNav, 5);
    QVERIFY2(band == HTCAPTION, qPrintable(hitName(band)));
    const LRESULT nav = hitTestAt("navRow", 0, 5);
    QVERIFY2(nav == HTCLIENT, qPrintable(hitName(nav)));

    item("titleBar")->setEnabled(false);
    const LRESULT covered = hitTestAt("titleBar", leftOfNav, 5);
    item("titleBar")->setEnabled(true);
    QVERIFY2(covered == HTCLIENT, qPrintable(QStringLiteral("a disabled title bar still answers ")
                                             + hitName(covered)));
    const LRESULT again = hitTestAt("titleBar", leftOfNav, 5);
    QVERIFY2(again == HTCAPTION, qPrintable(hitName(again)));
}

// The thin border and the system menu follow the app's own light and dark, through
// the agent's "dark-mode" attribute, whenever the window's darkChrome changes.
void TestWindowsChrome::followingTheDarkSwitchDoesNotUndoTheChrome()
{
    m_window->setProperty("darkChrome", true);
    QTest::qWait(50);
    m_window->setProperty("darkChrome", false);
    QTest::qWait(50);
    QVERIFY(m_window->isVisible());
    const LRESULT maximise = hitTestAt("windowMaxButton");
    QVERIFY2(maximise == HTMAXBUTTON, qPrintable(hitName(maximise)));
}

QTEST_MAIN(TestWindowsChrome)
#include "test_windows_chrome.moc"
