"""The launcher opens the service window only when the service is not already answering."""

import json
import threading
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer
from unittest import mock

from workflow_service import supervisor

TOKEN = "t" * 40


class SupervisorTests(unittest.TestCase):
    def setUp(self):
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), supervisor.make_handler(TOKEN, "127.0.0.1"))
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.addCleanup(self.server.server_close)
        self.addCleanup(self.server.shutdown)
        self.base = f"http://127.0.0.1:{self.server.server_port}"

    def call(self, method, path, token=TOKEN):
        request = urllib.request.Request(self.base + path, method=method, data=b"{}" if method == "POST" else None,
                                         headers={"Authorization": f"Bearer {token}"})
        with urllib.request.urlopen(request, timeout=5) as response:
            return json.load(response)

    def test_launch_opens_the_window_when_the_service_is_down(self):
        with mock.patch.object(supervisor, "service_running", return_value=False), \
                mock.patch.object(supervisor.subprocess, "Popen") as popen:
            result = self.call("POST", "/launch")
        self.assertTrue(result["launched"])
        self.assertEqual(popen.call_args.args[0][:2], ["cmd.exe", "/k"])
        self.assertTrue(popen.call_args.args[0][2].endswith("start-service.cmd"))

    def test_launch_never_starts_a_second_service(self):
        with mock.patch.object(supervisor, "service_running", return_value=True), \
                mock.patch.object(supervisor.subprocess, "Popen") as popen:
            self.assertFalse(self.call("POST", "/launch")["launched"])
        popen.assert_not_called()

    def test_requires_the_token_and_exposes_nothing_else(self):
        with mock.patch.object(supervisor.subprocess, "Popen") as popen:
            for method, path in (("POST", "/launch"), ("GET", "/status")):
                with self.assertRaises(urllib.error.HTTPError) as denied:
                    self.call(method, path, token="wrong")
                self.assertEqual(denied.exception.code, 401)
            with self.assertRaises(urllib.error.HTTPError) as missing:
                self.call("POST", "/run-anything")
            self.assertEqual(missing.exception.code, 404)
        popen.assert_not_called()


if __name__ == "__main__":
    unittest.main()
