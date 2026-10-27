"""Offline regression checks for documented packet shapes; no hardware connection."""
import unittest
import bond_ble_probe as probe

class PacketTests(unittest.TestCase):
 def test_motor_size_and_endian(self):
  p=probe.build_v2_single_vibration(80,2000)
  self.assertEqual(len(p),37)
  self.assertEqual(p[:7],bytes.fromhex('26 00 00 50 d0 07 04'))
  self.assertEqual(p[17:],bytes(20))
 def test_pairing_and_queue_padding(self):
  self.assertEqual(probe.build_v2_paired_correct(),bytes.fromhex('31 00 11 22 33 44')+bytes(14))
  self.assertEqual(probe.build_v2_execute_queue(),bytes.fromhex('32 00 11')+bytes(17))
  self.assertEqual(probe.build_v2_execute_queue(True),bytes.fromhex('32 00 22')+bytes(17))
 def test_auid_generation_difference(self):
  self.assertEqual(probe.build_v2_auid(0x12345678),bytes.fromhex('78 56 34 12'))
  self.assertEqual(probe.build_v3_auid(0x12345678),bytes.fromhex('07 78'))
 def test_v3_auth(self):
  self.assertEqual(probe.build_v3_init_auth(),bytes.fromhex('01 30'))
  self.assertEqual(probe.build_v3_token(),bytes.fromhex('03 31 32 33 34 35 36 37 38'))
  self.assertEqual(probe.build_v3_paired_correct(),bytes.fromhex('01 31'))
 def test_v3_config_is_not_motor_packet(self):
  self.assertEqual(probe.build_v3_vibration_intensity(80,2),bytes.fromhex('01 48 00 50 02 03'))
if __name__=='__main__':unittest.main()
