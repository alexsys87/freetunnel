package org.freetunnel.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// The licenses card's text: a file written for 80 columns, set for a box of about 55, and the notices' Markdown without its marks.
public final class LicenseTextTest {
    @Test public void aFileIsListedByItsNameAndNotItsFolder() {
        assertEquals("zlib.txt", LicenseText.name("licenses/common/zlib.txt"));
        assertEquals("EPL-1.0.txt", LicenseText.name("licenses/android/EPL-1.0.txt"));
        assertEquals("LICENSE", LicenseText.name("LICENSE"));
        assertEquals("THIRD_PARTY_NOTICES.md", LicenseText.name("THIRD_PARTY_NOTICES.md"));
    }

    @Test public void theLinesOfAParagraphAreJoinedWhereTheSourceWrappedThem() {
        String source = "Permission is hereby granted, free of charge, to any person obtaining\n"
                + "a copy of this software and associated documentation files, to deal in\n"
                + "the Software without restriction.\n"
                + "\n"
                + "The above copyright notice shall be included.\n";
        assertEquals("Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated "
                + "documentation files, to deal in the Software without restriction.\n\nThe above copyright notice shall be included.\n",
                LicenseText.reflow(source, false));
    }

    @Test public void aShortLineEndsWhereItsAuthorEndedIt() {
        String source = "Copyright (c) 2000 Foo\nCopyright (c) 2001 Bar\nAll rights reserved.\n";
        assertEquals(source, LicenseText.reflow(source, false));
    }

    @Test public void anItemStartsALineAndItsContinuationJoinsIt() {
        String source = "   (a) You must give any other recipients of the Work or Derivative\n"
                + "       Works a copy of this License; and\n"
                + "   (b) You must cause any modified files to carry prominent notices\n"
                + "       stating that You changed the files; and\n";
        assertEquals("   (a) You must give any other recipients of the Work or Derivative Works a copy of this License; and\n"
                + "   (b) You must cause any modified files to carry prominent notices stating that You changed the files; and\n",
                LicenseText.reflow(source, false));
    }

    @Test public void manyBlankLinesAreOneAndTheEndsAreTrimmed() {
        assertEquals("one\n\ntwo\n", LicenseText.reflow("\n\none\n\n\n\ntwo\n\n\n", false));
    }

    @Test public void markdownLosesItsMarksAndKeepsItsStructure() {
        String source = "# Third-party notices\n"
                + "\n"
                + "FreeTunnel is licensed under the Apache License 2.0, given in full in\n"
                + "[LICENSE](LICENSE). Its downloads also contain [the VPN core and its\n"
                + "libraries](#the-vpn-core) and **more** (see `licenses/`).\n"
                + "\n"
                + "- `licenses/common/`: components in every download\n"
                + "  (the VPN core's libraries).\n"
                + "- [Site](https://example.org/x): a web link.\n"
                + "\n"
                + "## Table\n"
                + "\n"
                + "| Name | License |\n"
                + "|------|---------|\n"
                + "| zlib | zlib    |\n";
        assertEquals("Third-party notices\n"
                + "\n"
                + "FreeTunnel is licensed under the Apache License 2.0, given in full in LICENSE. Its downloads also contain the VPN core and its "
                + "libraries and more (see licenses/).\n"
                + "\n"
                + "• licenses/common/: components in every download (the VPN core's libraries).\n"
                + "• Site (https://example.org/x): a web link.\n"
                + "\n"
                + "Table\n"
                + "\n"
                + "Name  ·  License\n"
                + "zlib  ·  zlib\n",
                LicenseText.reflow(source, true));
    }

    @Test public void aFencedBlockIsLeftAsItIs() {
        String source = "Before.\n\n```\n  indented   code\n  stays\n```\n\nAfter.\n";
        String shown = LicenseText.reflow(source, true);
        assertTrue(shown, shown.contains("  indented   code\n  stays\n"));
        assertFalse(shown, shown.contains("```"));
    }
}
