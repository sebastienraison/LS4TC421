package net.rseb.ls4tc421

import net.rseb.ls4tc421.data.Ipv4
import net.rseb.ls4tc421.data.SsidRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesTest {
    @Test fun ipv4_acceptsLiteralsOnly() {
        assertEquals("192.0.2.10", Ipv4.parse(" 192.0.2.10 ")!!.hostAddress)
        assertNotNull(Ipv4.parse("255.255.255.255"))
        for (bad in listOf("", "256.0.0.1", "1.2.3", "1.2.3.4.5", "a.b.c.d", "host.example", "1.2.3.-4", "0x7f.0.0.1")) {
            assertNull(bad, Ipv4.parse(bad))
        }
    }

    @Test fun ssid_cleaning() {
        assertEquals("MyNet", SsidRules.clean("\"MyNet\""))
        assertNull(SsidRules.clean("<unknown ssid>"))
        assertNull(SsidRules.clean("\"\""))
        assertNull(SsidRules.clean(null))
    }

    @Test fun controllerAccessPoint_isRecognised() {
        for (ap in listOf("Smarttime 12AB", "smart-0a1b2c", "Smart-0A1B2C")) {
            assertTrue(ap, SsidRules.isControllerAccessPoint(ap))
        }
        for (other in listOf("Smart-0A1B2", "Smart-0A1B2G", "Smartphone", "tc421", "Smart-0A1B2C3", "MySmart-0A1B2C")) {
            assertFalse(other, SsidRules.isControllerAccessPoint(other))
        }
    }

    @Test fun controllerAccessPoint_isNeverRemembered() {
        assertFalse(SsidRules.shouldRemember("Smart-0A1B2C"))
        assertFalse(SsidRules.shouldRemember("Smarttime 1234"))
        assertFalse(SsidRules.shouldRemember("smarttime-abcd"))
        assertFalse(SsidRules.shouldRemember(null))
        assertTrue(SsidRules.shouldRemember("MyNet"))
    }
}
