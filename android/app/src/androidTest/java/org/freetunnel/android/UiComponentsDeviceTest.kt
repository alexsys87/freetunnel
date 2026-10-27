package org.freetunnel.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.view.View.MeasureSpec
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

// The desktop's controls as Ui builds them: the measurements of qml/Toggle.qml, qml/Field.qml,
// qml/components/* and qml/pages/*, in dp, and which look each rests in, with the focus, with and
// without a remote. Built and measured here, off the screen; TvDeviceTest checks the real pages.
class UiComponentsDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val density get() = context.resources.displayMetrics.density
    private fun dp(n: Float) = Math.round(n * density)
    private fun dp(n: Int) = dp(n.toFloat())

    private fun ui(dark: Boolean = true, remote: Boolean = false) = Ui(context).apply { update(dark, remote) }
    // The size a view takes in a space this wide and, if it is given, this tall.
    private fun measured(view: View, width: Int = 0, height: Int = 0): View {
        val w = if (width > 0) MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST) else MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        val h = if (height > 0) MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST) else MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        view.measure(w, h)
        return view
    }
    private fun assertNear(message: String, expected: Int, actual: Int, delta: Int) =
        assertTrue("$message: expected $expected, was $actual", Math.abs(expected - actual) <= delta)
    private fun onMain(block: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync { try { block() } catch (t: Throwable) { failure = t } }
        failure?.let { throw it }
    }

    private fun fill(drawable: Drawable) = (drawable as GradientDrawable).color?.defaultColor ?: Color.TRANSPARENT
    private fun edge(drawable: Drawable): Int {
        val bitmap = Bitmap.createBitmap(160, 240, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, 160, 240)
        drawable.draw(Canvas(bitmap))
        return bitmap.getPixel(0, 120)
    }
    private fun looks(view: View): Map<String, Drawable> {
        val states = view.background as StateListDrawable
        val out = HashMap<String, Drawable>()
        for ((name, state) in listOf("rest" to intArrayOf(), "pressed" to intArrayOf(android.R.attr.state_pressed),
                "focused" to intArrayOf(android.R.attr.state_focused))) {
            states.setState(state)
            out[name] = states.current
        }
        return out
    }

    @Test fun theToggleIs38By22WithTheFocusAroundItAndFlipsOnATap() {
        onMain {
            val ui = ui()
            val big = measured(ui.toggle(false, false, "t:big")) as ToggleView
            // The track is 38 by 22, and the view 4 larger on each side, which the focus fills.
            assertEquals(dp(46), big.measuredWidth)
            assertEquals(dp(30), big.measuredHeight)
            val small = measured(ui.toggle(false, true, "t:small"))
            assertEquals("Logs' toggle is 34 by 20", dp(42), small.measuredWidth)
            assertEquals(dp(28), small.measuredHeight)
            var flips = 0
            var last = false
            big.setListener { _, checked -> flips++; last = checked }
            assertFalse(big.isChecked)
            big.performClick()
            assertTrue("A tap flips it", big.isChecked)
            assertEquals(1, flips)
            assertTrue(last)
            big.performClick()
            assertFalse(big.isChecked)
            assertEquals(2, flips)
            big.isEnabled = false
            assertEquals("Disabled it is dimmed", 0.45f, big.alpha, 0.001f)
            // TalkBack reads it as a switch, with its state.
            val info = big.createAccessibilityNodeInfo()
            assertEquals("android.widget.Switch", info.className.toString())
            assertTrue(info.isCheckable)
        }
    }

    @Test fun buttonsAre32HighWithTheDesktopsMinimumWidthsAndNoPressedAnimation() {
        onMain {
            val ui = ui()
            val cancel = measured(ui.button("Cancel", Ui.Kind.NEUTRAL, 76, "b:cancel"))
            assertEquals("A dialog's button is 32 high", dp(32), cancel.measuredHeight)
            assertTrue("and at least 76 wide", cancel.measuredWidth >= dp(76))
            val save = measured(ui.button("Save", Ui.Kind.PRIMARY, 88, "b:save"))
            assertTrue("An editor's button is at least 88 wide", save.measuredWidth >= dp(88))
            val long = measured(ui.button("A label that is wider than the minimum", Ui.Kind.NEUTRAL, 76, "b:long")) as TextView
            val words = Math.ceil(long.paint.measureText(long.text.toString()).toDouble()).toInt()
            assertNear("A long label is its width plus 26", words + dp(26), long.measuredWidth, 2)
            assertNull("No pressed animation: Material's lift is off", long.stateListAnimator)
            assertEquals("Normal weight", false, long.typeface.isBold)
        }
    }

    // The desktop's weights are Normal and Medium (500): nothing is bold.
    @Test fun mediumIsNeverBold() {
        onMain {
            val ui = ui()
            val name = ui.medium("DE · Frankfurt", 15f, Color.WHITE)
            assertEquals(Ui.MEDIUM, name.typeface)
            assertFalse(name.typeface.isBold)
            val mono = ui.mono("log", 11f, Color.WHITE)
            assertFalse(mono.typeface.isBold)
            assertEquals(android.graphics.Typeface.MONOSPACE, mono.typeface)
            assertEquals("A section label is as it is typed", "Rules — bypass VPN", ui.sectionLabel("Rules — bypass VPN").text.toString())
        }
    }

    @Test fun aFlowWrapsItsChildrenAndSpacesRowsAsItDoesColumns() {
        onMain {
            val flow = FlowLayout(context, dp(6))
            repeat(5) { flow.addView(View(context), FrameLayout.LayoutParams(dp(100), dp(28))) }
            flow.measure(MeasureSpec.makeMeasureSpec(dp(260), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            flow.layout(0, 0, flow.measuredWidth, flow.measuredHeight)
            // 100 + 6 + 100 fits in 260; a third would not.
            assertEquals("Three rows of two, two and one", 3 * dp(28) + 2 * dp(6), flow.measuredHeight)
            assertEquals(dp(100) + dp(6), flow.getChildAt(1).left)
            assertEquals("The third child starts the second row", 0, flow.getChildAt(2).left)
            assertEquals(dp(28) + dp(6), flow.getChildAt(2).top)
            assertEquals(2 * (dp(28) + dp(6)), flow.getChildAt(4).top)
        }
    }

    @Test fun aChipIs28HighAndItsXIsTwentyTwoWideThreeFromTheLabel() {
        onMain {
            val ui = ui()
            val plain = measured(ui.chip("Default", false, false, null, false))
            assertEquals("A chip is 28 high", dp(28), plain.measuredHeight)
            val withX = ui.chip("Work", false, false, null, false)
            withX.addView(ui.chipX("domain:0", false), ui.chipXLp())
            measured(withX)
            val label = Math.ceil((withX.getChildAt(0) as TextView).paint.measureText("Work").toDouble()).toInt()
            // 11 in, the label, 3, the cell 22 less 8 beyond the padding, 11: the label and 39.
            assertNear("A chip with its X", label + dp(39), withX.measuredWidth, 3)
            assertEquals(dp(28), withX.measuredHeight)
        }
    }

    @Test fun aDropdownHasTheValueAndItsArrowAsItsOnlyControl() {
        onMain {
            val ui = ui()
            val row = ui.dropdown("Mode", "Bypass VPN", "split:mode") {}
            val group = row.findViewWithTag<View>("split:mode")
            assertNotNull(group)
            var focusStops = 0
            fun walk(view: View) {
                if (view.isFocusable) focusStops++
                if (view is android.view.ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
            }
            walk(row)
            assertEquals("Only the value and its ▾ are a stop, not the label", 1, focusStops)
            measured(row, dp(360))
            assertTrue("The row is 42 high at least", row.measuredHeight >= dp(42))
            assertTrue("The control hugs its value and its arrow", group.measuredWidth < dp(150))
        }
    }

    // Strong fills rest in neutral ones with a remote, so that the focus is the one strong fill.
    @Test fun withARemoteOnlyTheFocusIsAStrongFill() {
        onMain {
            for (dark in listOf(true, false)) {
                val theme = Theme.of(dark)
                val touch = ui(dark, remote = false)
                val remote = ui(dark, remote = true)
                val primaryTouch = looks(touch.button("Save", Ui.Kind.PRIMARY, 88, "b:p"))
                val primaryRemote = looks(remote.button("Save", Ui.Kind.PRIMARY, 88, "b:p"))
                assertEquals("A primary button rests on the accent", theme.accent, fill(primaryTouch["rest"]!!))
                assertEquals("and with a remote on surface", theme.surface, fill(primaryRemote["rest"]!!))
                assertEquals("it goes darker under the pointer", theme.primaryHover, fill(primaryTouch["pressed"]!!))
                assertEquals("and has the accent with the focus", theme.accent, fill(primaryRemote["focused"]!!))
                val dangerTouch = looks(touch.button("Delete", Ui.Kind.DESTRUCTIVE, 76, "b:d"))
                val dangerRemote = looks(remote.button("Delete", Ui.Kind.DESTRUCTIVE, 76, "b:d"))
                assertEquals("A destructive button rests on danger", theme.danger, fill(dangerTouch["rest"]!!))
                assertEquals("and with a remote on surface", theme.surface, fill(dangerRemote["rest"]!!))
                assertEquals(theme.destructiveHover, fill(dangerTouch["pressed"]!!))
                assertEquals("It focuses in danger either way", theme.danger, fill(dangerRemote["focused"]!!))
                assertEquals(theme.danger, fill(dangerTouch["focused"]!!))
                val neutral = looks(touch.button("Cancel", Ui.Kind.NEUTRAL, 76, "b:n"))
                assertEquals(theme.surface, fill(neutral["rest"]!!))
                assertEquals(theme.surfaceHover, fill(neutral["pressed"]!!))
                assertEquals(theme.accent, fill(neutral["focused"]!!))
                val secondary = looks(touch.button("Cancel", Ui.Kind.SECONDARY, 88, "b:s"))
                assertEquals("The editor's Cancel rests on the page", theme.bg, fill(secondary["rest"]!!))
                assertEquals(theme.border, edge(secondary["rest"]!!))
                assertEquals(theme.surface, fill(secondary["pressed"]!!))
                val selectedTouch = looks(touch.chip("Work", true, true, "profile:1", false))
                val selectedRemote = looks(remote.chip("Work", true, true, "profile:1", false))
                assertEquals("The selected chip rests on the accent", theme.accent, fill(selectedTouch["rest"]!!))
                assertEquals("and with a remote on infoBg", theme.infoBg, fill(selectedRemote["rest"]!!))
                val other = looks(touch.chip("Default", false, true, "profile:0", false))
                assertEquals(theme.surface, fill(other["rest"]!!))
                assertEquals(theme.surfaceHover, fill(other["pressed"]!!))
                assertEquals(theme.accent, fill(other["focused"]!!))
            }
        }
    }

    // A text field keeps the desktop's own focus, an accent border, and with a remote also surface.
    @Test fun aFieldTurnsItsBorderAccentWithTheFocus() {
        onMain {
            for (dark in listOf(true, false)) {
                val theme = Theme.of(dark)
                val touch = looks(ui(dark, remote = false).pageInput("domains, comma-separated, then Enter", "domains:input"))
                assertEquals(theme.inputBg, fill(touch["rest"]!!))
                assertEquals(theme.inputBorder, edge(touch["rest"]!!))
                assertEquals(theme.accent, edge(touch["focused"]!!))
                assertEquals("On a phone the fill stays", theme.inputBg, fill(touch["focused"]!!))
                val remote = looks(ui(dark, remote = true).pageInput("domains", "domains:input"))
                assertEquals("With a remote it also reads from further away", theme.surface, fill(remote["focused"]!!))
                assertEquals(theme.accent, edge(remote["focused"]!!))
            }
            val ui = ui()
            val input = EditText(context)
            val box = ui.field("Name", input, "editor:name")
            measured(box, dp(300))
            assertEquals("A field's box is 34 high", dp(34), input.measuredHeight)
        }
    }

    // A link is its words, wrap_content, and a link in the danger colour focuses in danger.
    @Test fun aLinkHugsItsWordsAndADangerLinkFocusesInDanger() {
        onMain {
            val theme = Theme.DARK
            val ui = ui()
            val link = measured(ui.link("Recommended for Russia", 12f, Ui.LinkKind.ACCENT, "domains:russia"), dp(400)) as TextView
            val words = Math.ceil(link.paint.measureText(link.text.toString()).toDouble()).toInt()
            assertNear("A link is its words and a little around them", words + link.paddingLeft + link.paddingRight, link.measuredWidth, 2)
            assertEquals(theme.accent, link.textColors.defaultColor)
            val danger = ui.link("Clear all", 12f, Ui.LinkKind.DANGER, "domains:clear")
            assertEquals(theme.danger, danger.textColors.defaultColor)
            assertEquals(theme.danger, fill(looks(danger)["focused"]!!))
            assertEquals("White on it", theme.onDanger, danger.textColors.getColorForState(intArrayOf(android.R.attr.state_focused), 0))
            val text = ui.link("Add a config", 15f, Ui.LinkKind.TEXT, "configs:hint")
            assertEquals(theme.text, text.textColors.defaultColor)
            assertEquals("A text link goes accent under the pointer", theme.accent, text.textColors.getColorForState(intArrayOf(android.R.attr.state_pressed), 0))
        }
    }

    // The parts of a card (the editor, the app picker, Receive from phone, the licenses): the header is 48 high with the arrow in a cell
    // of its own and the title in Medium; the editor's text area has a Field's look in monospace 12 and grows from its minimum; its
    // toggle row is 32 high; and a box of text that a remote scrolls rests on surface and takes the field's accent border.
    @Test fun aCardsHeaderTextAreaToggleRowAndBoxOfTextAreAsTheDesktopDrawsThem() {
        onMain {
            val theme = Theme.DARK
            val ui = ui()
            val header = measured(ui.cardHeader("New config", "editor:back", "Back") {}, dp(372)) as android.view.ViewGroup
            assertEquals("A card's header is 48 high", dp(48), header.measuredHeight)
            val arrow = header.findViewWithTag<TextView>("editor:back")
            assertEquals("\u2190", arrow.text.toString())
            assertEquals("The arrow is textDim", theme.textDim, arrow.textColors.defaultColor)
            assertEquals("and the text colour under the pointer", theme.text, arrow.textColors.getColorForState(intArrayOf(android.R.attr.state_pressed), 0))
            assertEquals("A remote's focus is the accent", theme.accent, fill(looks(arrow)["focused"]!!))
            assertEquals("Back", arrow.contentDescription.toString())
            val title = header.getChildAt(1) as TextView
            assertEquals("The title is Medium, never bold", Ui.MEDIUM, title.typeface)
            assertEquals("New config", title.text.toString())

            val area = EditText(context)
            val field = ui.fieldArea("TOML", area, "editor:toml", 150)
            measured(field, dp(300))
            assertTrue("The text area is at least 150 high", area.measuredHeight >= dp(150))
            assertEquals(android.graphics.Typeface.MONOSPACE, area.typeface)
            val areaLooks = looks(area)
            assertEquals("It is a Field: inputBg with an inputBorder", theme.inputBg, fill(areaLooks["rest"]!!))
            assertEquals(theme.accent, edge(areaLooks["focused"]!!))

            val row = measured(ui.editorToggleRow("Show TOML and password", ui.toggle(false, false, "editor:show")), dp(300))
            assertTrue("A toggle row of the editor is 32 high at least", row.measuredHeight >= dp(32))

            val box = android.widget.ScrollView(context)
            ui.focus(box, "licenses:text", Ui.Look.BOX)
            val boxLooks = looks(box)
            assertEquals("A box of text rests on surface", theme.surface, fill(boxLooks["rest"]!!))
            assertEquals("without a border", theme.surface, edge(boxLooks["rest"]!!))
            assertEquals("and takes the field's accent border with the focus", theme.accent, edge(boxLooks["focused"]!!))
            assertEquals(theme.surface, fill(boxLooks["focused"]!!))
        }
    }

    // Every icon is made from the desktop's SVG path data: each loads, at the size the desktop draws it.
    @Test fun everyVectorDrawableLoads() {
        for ((id, size) in listOf(R.drawable.ic_connection to 24, R.drawable.ic_network to 24, R.drawable.ic_settings to 24,
                R.drawable.ic_log to 24, R.drawable.ic_ping to 24, R.drawable.ic_export to 24, R.drawable.ic_edit to 24,
                R.drawable.ic_close to 24, R.drawable.ic_logo to 100, R.drawable.ic_add to 22, R.drawable.ic_add_home to 14)) {
            val drawable = context.getDrawable(id)
            assertNotNull(drawable)
            // Its own size, in dp: a pixel either way is the rounding.
            assertNear("Drawable $id is $size wide", dp(size), drawable!!.intrinsicWidth, 1)
            // Drawn: something is painted in it, not just parsed.
            val bitmap = Bitmap.createBitmap(dp(size), dp(size), Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, dp(size), dp(size))
            drawable.draw(Canvas(bitmap))
            var painted = 0
            for (x in 0 until bitmap.width) for (y in 0 until bitmap.height) if (Color.alpha(bitmap.getPixel(x, y)) != 0) painted++
            assertTrue("Drawable $id paints nothing", painted > 0)
        }
    }

    @Test fun iconsTakeTheirColourFromAndOnlyFromTheirTint() {
        onMain {
            val ui = ui()
            val x = ui.chipX("domain:0", false)
            assertEquals("A ChipX is textDim at rest", Theme.DARK.textDim, x.imageTintList!!.defaultColor)
            assertEquals("and white with the focus, on its danger circle", Theme.DARK.onDanger,
                x.imageTintList!!.getColorForState(intArrayOf(android.R.attr.state_focused), 0))
            assertEquals("Danger under the pointer", Theme.DARK.danger,
                x.imageTintList!!.getColorForState(intArrayOf(android.R.attr.state_pressed), 0))
        }
    }

    // Home's selector is its name and ▾ and no wider, 6 apart, in the text colour and the accent under the pointer, in Medium,
    // never bold; with the focus the accent around them and accentText in them. Without configs it is "Add a config" alone.
    @Test fun homesSelectorIsAsWideAsItsNameAndArrowAndFocusesAsALink() {
        onMain {
            val theme = Theme.DARK
            val ui = ui()
            val selector = measured(ui.selector("DE \u00b7 Frankfurt", true, 0, "home:config"), dp(400)) as android.view.ViewGroup
            val name = selector.getChildAt(0) as TextView
            val arrow = selector.getChildAt(1) as TextView
            assertEquals("\u25be", arrow.text.toString())
            assertEquals("The name is Medium, never bold", Ui.MEDIUM, name.typeface)
            assertEquals(theme.text, name.textColors.defaultColor)
            assertEquals("and the accent under the pointer", theme.accent, name.textColors.getColorForState(intArrayOf(android.R.attr.state_hovered), 0))
            assertEquals("The arrow follows it", theme.accent, arrow.textColors.getColorForState(intArrayOf(android.R.attr.state_hovered), 0))
            assertEquals("accentText with the focus", theme.accentText, name.textColors.getColorForState(intArrayOf(android.R.attr.state_focused), 0))
            assertNear("As wide as the name, 6, the arrow and its own padding", name.measuredWidth + dp(6) + arrow.measuredWidth + dp(12), selector.measuredWidth, 2)
            assertTrue("Far from the width of the screen", selector.measuredWidth < dp(300))
            val looks = looks(selector)
            assertEquals("It rests on the page", Color.TRANSPARENT, fill(looks["rest"]!!))
            assertEquals("and the accent is its focus", theme.accent, fill(looks["focused"]!!))
            // A long name is cut at the room it is given, and the arrow stays after what shows.
            val long = measured(ui.selector("A very very very long name of a server that goes on", true, dp(120), "home:config"), dp(400)) as android.view.ViewGroup
            assertTrue("The name takes at most the room", (long.getChildAt(0) as TextView).measuredWidth <= dp(120))
            assertEquals(android.text.TextUtils.TruncateAt.END, (long.getChildAt(0) as TextView).ellipsize)
            // With nothing to choose from it is the words alone.
            val empty = ui.selector("Add a config", false, 0, "home:config")
            assertEquals("No \u25be", 1, empty.childCount)
        }
    }

    // A speed tile: the arrow in its colour and the number in Medium 15; a badge: 11 in Medium on infoBg, 8 in, whose text and fill
    // follow a row's focus.
    @Test fun aSpeedTileAndABadgeAreAsTheDesktopDrawsThem() {
        onMain {
            val theme = Theme.DARK
            val ui = ui()
            val value = arrayOfNulls<TextView>(1)
            val tile = ui.speedTile("\u2193", theme.success, value)
            assertEquals(theme.success, (tile.getChildAt(0) as TextView).currentTextColor)
            assertEquals("\u2193", (tile.getChildAt(0) as TextView).text.toString())
            assertSame(value[0], tile.getChildAt(1))
            assertEquals("The number is Medium", Ui.MEDIUM, value[0]!!.typeface)
            assertEquals(theme.text, value[0]!!.currentTextColor)
            assertFalse("A tile is no control", tile.isFocusable || tile.isClickable)
            assertEquals("It rests on the tile colour", theme.tile, fill(tile.background))

            val badge = measured(ui.badge("connected", theme.success)) as TextView
            assertEquals(Ui.MEDIUM, badge.typeface)
            assertEquals(theme.success, badge.textColors.defaultColor)
            assertEquals("accentText on a row's accent", theme.accentText, badge.textColors.getColorForState(intArrayOf(android.R.attr.state_focused), 0))
            val words = Math.ceil(badge.paint.measureText("connected").toDouble()).toInt()
            assertNear("8 in from each side", words + dp(16), badge.measuredWidth, 2)
            val states = badge.background as StateListDrawable
            states.setState(intArrayOf())
            assertEquals("on infoBg", theme.infoBg, fill(states.current))
        }
    }

    // A config row: its logo, its name taking what is left, the three icons in cells 26 wide at the right 6 from the edge, each 40 high in the
    // 48 of the row; it rests on the page, on surface while one of its icons has the focus, and is the accent when it has it itself, with the
    // icons on it in accentText; a close icon's cell focuses in danger.
    @Test fun aConfigRowIsLaidOutAndLooksAsTheDesktopsDoes() {
        onMain {
            val theme = Theme.DARK
            val ui = ui()
            val row = ConfigRowView(context, ui, 1, "DE \u00b7 Frankfurt", true, "42 ms", "connected", true, "Export config", "Edit config", "Delete")
            row.measure(MeasureSpec.makeMeasureSpec(dp(400), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(dp(48), MeasureSpec.EXACTLY))
            row.layout(0, 0, dp(400), dp(48))
            assertEquals("config:1:select", row.tag)
            assertEquals(dp(26), row.removeIcon.width)
            assertEquals(dp(40), row.removeIcon.height)
            assertNear("The icons end 6 from the edge", dp(400) - dp(6), row.removeIcon.right, 1)
            assertEquals("side by side", row.removeIcon.left, row.editIcon.right)
            assertEquals(row.editIcon.left, row.exportIcon.right)
            assertTrue("The name has room", (row.getChildAt(1) as TextView).width > dp(100))
            assertNear("The logo is 22", dp(22), row.getChildAt(0).width, 1)
            assertEquals(listOf("config:1:export", "config:1:edit", "config:1:remove"), listOf(row.exportIcon, row.editIcon, row.removeIcon).map { it.tag })
            assertEquals("Edit config", row.editIcon.contentDescription)

            val states = row.background as StateListDrawable
            states.setState(intArrayOf())
            assertEquals("It rests on the page", Color.TRANSPARENT, fill(states.current))
            states.setState(intArrayOf(android.R.attr.state_activated))
            assertEquals("and on surface while an icon of it has the focus", theme.surface, fill(states.current))
            states.setState(intArrayOf(android.R.attr.state_pressed))
            assertEquals("and under the pointer", theme.surface, fill(states.current))
            states.setState(intArrayOf(android.R.attr.state_focused))
            assertEquals("With the focus it is the accent", theme.accent, fill(states.current))

            assertEquals("An icon's cell takes the accent", theme.accent, fill(looks(row.editIcon)["focused"]!!))
            assertEquals("and the close icon's takes danger", theme.danger, fill(looks(row.removeIcon)["focused"]!!))
            val tint = (row.editIcon.getChildAt(0) as android.widget.ImageView).imageTintList!!
            assertEquals("An icon is textDim", theme.textDim, tint.defaultColor)
            assertEquals("the text colour under the pointer", theme.text, tint.getColorForState(intArrayOf(android.R.attr.state_pressed), 0))
            assertEquals("accentText on its accent cell", theme.accentText, tint.getColorForState(intArrayOf(android.R.attr.state_focused), 0))
            assertEquals("and on the row's accent", theme.accentText, tint.getColorForState(intArrayOf(android.R.attr.state_selected), 0))
            val close = (row.removeIcon.getChildAt(0) as android.widget.ImageView).imageTintList!!
            assertEquals("The close icon turns danger under the pointer", theme.danger, close.getColorForState(intArrayOf(android.R.attr.state_pressed), 0))
            assertEquals("and white on its danger cell", theme.onDanger, close.getColorForState(intArrayOf(android.R.attr.state_focused), 0))

            // A row without a ping or a badge has neither, and the name takes the room.
            val plain = ConfigRowView(context, ui, 0, "Name", false, null, null, false, "Export config", "Edit config", "Delete")
            assertEquals("logo, name and three icons", 5, plain.childCount)
            assertEquals("The other configs' logo is dim", 0.4f, plain.getChildAt(0).alpha, 0.001f)
        }
    }

    // The logo as a control has the nav tile's proportions at 132, and the accent for its focus, and nothing else.
    @Test fun theLogoControlFocusesInTheAccentOnAWideRadius() {
        onMain {
            val theme = Theme.DARK
            val box = FrameLayout(context)
            ui().focus(box, "home:toggle", Ui.Look.HERO)
            val looks = looks(box)
            assertEquals("It rests on the page", Color.TRANSPARENT, fill(looks["rest"]!!))
            assertEquals("and the accent is its focus", theme.accent, fill(looks["focused"]!!))
            assertEquals("with no hover of its own", Color.TRANSPARENT, fill(looks["pressed"]!!))
            assertEquals(dp(28).toFloat(), (looks["focused"] as GradientDrawable).cornerRadius, 0.5f)
            assertNull("A remote's focus is its background", box.foreground)
        }
    }
}
