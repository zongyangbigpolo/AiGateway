package com.v2ray.ang.util

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

class InvitationEntryTest {
    private val androidNamespace = "http://schemas.android.com/apk/res/android"

    private fun resource(path: String) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }.newDocumentBuilder().parse(File("src/main/res/$path"))

    @Test fun customerScreenHasOnlyAnInvitationCodeInput() {
        val document = resource("layout/activity_invitation.xml")
        val inputs = document.getElementsByTagName("EditText")
        assertEquals(1, inputs.length)
        val input = inputs.item(0) as Element
        assertEquals("@+id/et_code", input.getAttributeNS(androidNamespace, "id"))
        assertEquals("false", input.getAttributeNS(androidNamespace, "saveEnabled"))
        assertFalse(File("src/main/res/layout/activity_invitation.xml").readText().contains("et_service"))
    }

    @Test fun mainAddOpensInvitationWithoutManualServerSubmenu() {
        val document = resource("menu/menu_main.xml")
        val items = document.getElementsByTagName("item")
        val invitation = (0 until items.length).map { items.item(it) as Element }
            .single { it.getAttributeNS(androidNamespace, "id") == "@+id/import_invitation" }
        assertEquals(0, invitation.getElementsByTagName("menu").length)
        assertEquals(1, document.getElementsByTagName("menu").length)
    }
}
