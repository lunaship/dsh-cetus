import importlib.util
import pathlib
import tempfile
import unittest
from concurrent.futures import ThreadPoolExecutor
from threading import Barrier


MODULE_PATH = pathlib.Path(__file__).with_name("enroll-sidecar.py")
SPEC = importlib.util.spec_from_file_location("enroll_sidecar", MODULE_PATH)
sidecar = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(sidecar)


class InviteQuotaReservationTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        sidecar.DB = str(pathlib.Path(self.temp_dir.name) / "quota.db")
        sidecar.IP_MAX = 1
        sidecar.IP_WINDOW = 86400
        sidecar.GLOBAL_PER_MIN = 20

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_only_one_concurrent_request_reserves_per_ip_quota(self):
        barrier = Barrier(12)

        def reserve(index):
            barrier.wait()
            reservation_id = "request-%d" % index
            result = sidecar.reserve_invite_quota("192.0.2.5", reservation_id)
            return reservation_id if result is None else None

        with ThreadPoolExecutor(max_workers=12) as pool:
            reservations = [value for value in pool.map(reserve, range(12)) if value]

        self.assertEqual(len(reservations), 1)
        sidecar.release_invite_reservation(reservations[0])
        retry = sidecar.reserve_invite_quota("192.0.2.5", "retry-after-failure")
        self.assertIsNone(retry, "a failed mint must release its reservation")
        sidecar.record_invite_quota("192.0.2.5", "retry-after-failure")
        denied = sidecar.reserve_invite_quota("192.0.2.5", "after-success")
        self.assertEqual(denied[0], 429)
        self.assertEqual(denied[1]["error"], "already_issued")


if __name__ == "__main__":
    unittest.main()
