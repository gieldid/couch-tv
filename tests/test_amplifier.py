"""bridge/amplifier.py against a stand-in UPnP RenderingControl (shaped like a Denon PMA-900HNE's
answers), and the bridge routing the phone remote's volume to it. NOT a real amplifier."""
import asyncio
import re
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parents[1] / "bridge"))
from aiohttp import web  # noqa: E402
from aiohttp.test_utils import TestServer  # noqa: E402

import amplifier  # noqa: E402

NS = "urn:schemas-upnp-org:service:RenderingControl:1"
DESC = f"""<root><device><serviceList>
<service><serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
<controlURL>/upnp/control/renderer_dvc/ConnectionManager</controlURL></service>
<service><serviceType>{NS}</serviceType>
<controlURL>/upnp/control/renderer_dvc/RenderingControl</controlURL></service>
</serviceList></device></root>"""


def reply(action, inner=""):
    return (f'<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>'
            f'<u:{action}Response xmlns:u="{NS}">{inner}</u:{action}Response></s:Body></s:Envelope>')


FAULT = ('<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><s:Fault>'
         '<faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail>'
         '<UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>401</errorCode>'
         '<errorDescription>Invalid Action</errorDescription></UPnPError></detail></s:Fault>'
         '</s:Body></s:Envelope>')


class StandIn:
    def __init__(self):
        self.volume, self.mute, self.balance = 15, 0, 50
        self.sent = []

    async def control(self, request):
        action = request.headers["SOAPACTION"].strip('"').split("#")[1]
        body = await request.text()
        self.sent.append((action, body))
        arg = lambda tag: int(re.search(rf"<{tag}>(\d+)</{tag}>", body).group(1))
        if action == "GetVolume":
            return web.Response(text=reply(action, f"<CurrentVolume>{self.volume}</CurrentVolume>"))
        if action == "SetVolume":
            self.volume = arg("DesiredVolume")
            return web.Response(text=reply(action))
        if action == "GetMute":
            return web.Response(text=reply(action, f"<CurrentMute>{self.mute}</CurrentMute>"))
        if action == "SetMute":
            self.mute = arg("DesiredMute")
            return web.Response(text=reply(action))
        if action == "X_GetBalance":
            return web.Response(text=reply(action, f"<CurrentBalance>{self.balance}</CurrentBalance>"))
        if action == "X_SetBalance":
            self.balance = arg("DesiredBalance")
            return web.Response(text=reply(action))
        return web.Response(status=500, text=FAULT)

    def app(self):
        app = web.Application()
        app.router.add_post("/upnp/control/renderer_dvc/RenderingControl", self.control)
        app.router.add_get("/desc.xml", lambda r: web.Response(text=DESC))
        return app


class AmpTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.s = StandIn()
        self.server = TestServer(self.s.app())
        await self.server.start_server()
        self.amp = amplifier.from_config({"ip": "127.0.0.1", "port": self.server.port, "step": 2,
                                          "max_volume": 40, "name": "Denon"})

    async def asyncTearDown(self):
        await self.amp.close()
        await self.server.close()

    async def test_read_and_exact_soap(self):
        await self.amp.read()
        self.assertEqual(self.amp.volume, {"level": 15, "max": 100, "muted": False, "known": True,
                                           "device": "amplifier", "name": "Denon"})
        action, body = self.s.sent[0]
        self.assertEqual(action, "GetVolume")
        self.assertIn(f'<u:GetVolume xmlns:u="{NS}"><InstanceID>0</InstanceID><Channel>Master</Channel>', body)

    async def test_step_cap_and_mute(self):
        await self.amp.step_volume(1)
        self.assertEqual(self.s.volume, 17)
        await self.amp.step_volume(-1, steps=3)
        self.assertEqual(self.s.volume, 11)
        await self.amp.set_volume(90)   # max_volume keeps it at 40
        self.assertEqual(self.s.volume, 40)
        await self.amp.toggle_mute()
        self.assertEqual(self.s.mute, 1)
        await self.amp.toggle_mute()
        self.assertEqual(self.s.mute, 0)

    async def test_balance(self):
        self.assertEqual(await self.amp.get_balance(), 50)
        await self.amp.set_balance(32)
        self.assertEqual(self.s.balance, 32)
        self.assertIn("<DesiredBalance>32</DesiredBalance>", self.s.sent[-1][1])

    async def test_fault_is_an_error(self):
        with self.assertRaisesRegex(amplifier.AmpError, "401, Invalid Action"):
            await self.amp.call("X_Nonsense")

    async def test_finds_control_url_from_description(self):
        b = amplifier.from_config({"description_url": f"http://127.0.0.1:{self.server.port}/desc.xml"})
        try:
            self.assertTrue((await b.control_url()).endswith("/upnp/control/renderer_dvc/RenderingControl"))
            self.assertEqual(await b.get_volume(), 15)
        finally:
            await b.close()

    async def test_no_answer_is_an_error(self):
        port = self.server.port
        await self.server.close()
        c = amplifier.UpnpAmp({"ip": "127.0.0.1", "port": port, "timeout": 1})
        with self.assertRaises(amplifier.AmpError):
            await c.get_volume()
        await c.close()

    async def test_listener_on_change(self):
        seen = []
        self.amp.on_change(lambda: seen.append(1))
        await self.amp.set_volume(20)
        self.assertEqual(seen, [1])

    def test_config(self):
        self.assertIsNone(amplifier.from_config(None))
        self.assertIsNone(amplifier.from_config({"ip": "1.2.3.4", "enabled": False}))
        with self.assertRaises(ValueError):
            amplifier.from_config({"kind": "other", "ip": "1.2.3.4"})


class BridgeRouting(unittest.IsolatedAsyncioTestCase):
    """The bridge sends volume to the amplifier, not the TV, when the TV has one."""

    async def asyncSetUp(self):
        import server
        self.server_mod = server
        self.s = StandIn()
        self.ts = TestServer(self.s.app())
        await self.ts.start_server()
        self.tv_id = next(iter(server.TVS))
        self.saved = server.AMPS.get(self.tv_id)
        server.AMPS[self.tv_id] = amplifier.from_config({"ip": "127.0.0.1", "port": self.ts.port})
        self.keys = []

        async def fake_key(name, hold=False):
            self.keys.append(name)
        self.tv = server.TVS[self.tv_id]
        self.saved_key = self.tv.key
        self.tv.key = fake_key

    async def asyncTearDown(self):
        await self.server_mod.AMPS[self.tv_id].close()
        self.server_mod.AMPS[self.tv_id] = self.saved
        self.tv.key = self.saved_key
        await self.ts.close()

    async def call(self, handler, data, method="POST"):
        class Req:
            pass
        r = Req()
        r.method = method
        r.headers, r.query, r.remote = {}, {}, "127.0.0.1"

        async def js():
            return data
        r.json = js
        r.can_read_body = True
        r.body_exists = True
        server = self.server_mod
        token = server._current.set(self.tv_id)
        try:
            from unittest.mock import patch
            with patch.object(server, "body", lambda req: js()), patch.object(server, "record_action", lambda *a: None):
                return await handler(r)
        finally:
            server._current.reset(token)

    async def test_volume_and_keys_go_to_amp(self):
        server = self.server_mod
        await self.call(server.h_volume, {"action": "up"})
        self.assertEqual(self.s.volume, 17)
        await self.call(server.h_volume, {"action": "mute"})
        self.assertEqual(self.s.mute, 1)
        await self.call(server.h_volume, {"level": 30})
        self.assertEqual(self.s.volume, 30)
        await self.call(server.h_key, {"key": "volume_down"})
        self.assertEqual(self.s.volume, 28)
        await self.call(server.h_key, {"key": "home"})
        self.assertEqual(self.keys, ["home"])   # only non-volume keys reach the TV

    async def test_balance_endpoint(self):
        server = self.server_mod
        resp = await self.call(server.h_balance, {"balance": 45})
        self.assertEqual(self.s.balance, 45)
        self.assertIn(b'"balance": 45', resp.body)
        with self.assertRaises(server.ApiError):
            await self.call(server.h_balance, {"balance": "left"})


if __name__ == "__main__":
    unittest.main()
