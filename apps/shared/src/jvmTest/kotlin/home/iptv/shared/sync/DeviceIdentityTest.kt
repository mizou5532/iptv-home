package home.iptv.shared.sync
import kotlin.test.Test
import kotlin.test.assertEquals
class DeviceIdentityTest {
 @Test fun constructeurNomModele() {
  assertEquals("TCL 65QM851G", defaultDeviceName("TCL", "65QM851G"))
  assertEquals("TCL Google TV", defaultDeviceName("TCL", "TCL Google TV"))
  assertEquals("Télé Android", defaultDeviceName(null, "unknown"))
  assertEquals("Sony", defaultDeviceName(" Sony ", ""))
  assertEquals(31,defaultDeviceName("TCL", "X".repeat(80)).length)
 }
}
