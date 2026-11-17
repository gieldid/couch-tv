"""An amplifier that takes over the TV's volume: the phone remote's volume up, down and mute, the
volume slider and the assistant's volume keys go to the amplifier instead of the TV.

One kind so far, "upnp": a network amplifier or receiver with a UPnP RenderingControl:1 service, such
as a Denon or Marantz with HEOS. Confirmed on a Denon PMA-900HNE (description on port 60006, control
at /upnp/control/renderer_dvc/RenderingControl, volume 0 to 100, balance 0 to 100 with 50 in the
middle, read with X_GetBalance). The control URL can be given in config.json, or found by itself from
the description URL (the LOCATION an SSDP search answers with).

config.json, on the TV:

    "amplifier": {
      "kind": "upnp",
      "name": "Denon PMA-900HNE",
      "ip": "192.168.0.35",
      "port": 60006,
      "control_path": "/upnp/control/renderer_dvc/RenderingControl",
      "step": 2,
      "max_volume": 60
    }

Instead of "port" and "control_path", "description_url" (the full URL of the device description)
lets the bridge look the control URL up. "step" is how far one press of volume up or down moves it;
"max_volume" caps what the phone can set (a held button or the assistant never goes above it).
"""
import asyncio
import logging
import re
import time
from urllib.parse import urljoin
from xml.sax.saxutils import escape

import aiohttp

log = logging.getLogger("couchtv.amplifier")

SERVICE = "urn:schemas-upnp-org:service:RenderingControl:1"


class AmpError(Exception):
    """The amplifier did not answer, answered with an HTTP error, or sent a SOAP fault."""


def _soap_body(action, args):
    inner = "".join(f"<{k}>{escape(str(v))}</{k}>" for k, v in args)
    return ('<?xml version="1.0" encoding="utf-8"?>'
            '<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"'
            ' s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">'
            f'<s:Body><u:{action} xmlns:u="{SERVICE}">{inner}</u:{action}></s:Body></s:Envelope>')


def _fault(text):
    """A SOAP fault's UPnP error code and description, or None when the reply is not a fault."""
    if "Fault>" not in text:
        return None
    code = re.search(r"<(?:\w+:)?errorCode>\s*(\d+)\s*<", text)
    desc = re.search(r"<(?:\w+:)?errorDescription>([^<]*)<", text)
    fs = re.search(r"<faultstring>([^<]*)<", text)
    parts = [p for p in ((code and "UPnP error " + code.group(1)), (desc and desc.group(1).strip()),
                         (fs and not desc and fs.group(1).strip())) if p]
    return ", ".join(parts) or "SOAP fault"


def _out(text, tag):
    m = re.search(rf"<(?:\w+:)?{tag}>\s*([^<]*?)\s*</(?:\w+:)?{tag}>", text)
    if not m:
        raise AmpError(f"the amplifier's answer has no {tag}")
    return m.group(1)


class UpnpAmp:
    kind = "upnp"

    def __init__(self, cfg):
        self.name = cfg.get("name") or "Amplifier"
        self.ip = cfg.get("ip")
        self.port = int(cfg.get("port") or 60006)
        self.control_path = cfg.get("control_path")
        self.description_url = cfg.get("description_url")
        if not self.ip and not self.description_url:
            raise ValueError("amplifier: give ip (with port and control_path) or description_url")
        if not self.control_path and not self.description_url:
            self.control_path = "/upnp/control/renderer_dvc/RenderingControl"   # Denon / Marantz HEOS
        self.step = max(1, min(int(cfg.get("step") or 2), 10))
        self.max_volume = max(0, min(int(cfg.get("max_volume") if cfg.get("max_volume") is not None else 100), 100))
        self.timeout = float(cfg.get("timeout") or 3)
        self._control_url = (f"http://{self.ip}:{self.port}{self.control_path}" if self.control_path and self.ip
                             else None)
        self._session = None
        self._lock = asyncio.Lock()
        self._level = None
        self._muted = None
        self._read_at = 0.0
        self._refreshing = None
        self._listeners = set()

    # ---------- what the bridge shows ----------
    @property
    def volume(self):
        known = self._level is not None
        return {"level": self._level if known else None, "max": 100 if known else None,
                "muted": self._muted, "known": known, "device": "amplifier", "name": self.name}

    def on_change(self, fn):
        self._listeners.add(fn)

    def _changed(self):
        for fn in list(self._listeners):
            try:
                fn()
            except Exception:
                log.exception("amplifier listener failed")

    # ---------- SOAP ----------
    async def _http(self):
        if self._session is None or self._session.closed:
            # A fresh connection per request: the Denon's server closes a reused one without answering.
            self._session = aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=self.timeout),
                                                  connector=aiohttp.TCPConnector(force_close=True))
        return self._session

    async def control_url(self):
        if self._control_url:
            return self._control_url
        s = await self._http()
        try:
            async with s.get(self.description_url) as r:
                if r.status != 200:
                    raise AmpError(f"the amplifier's description answered HTTP {r.status}")
                desc = await r.text()
        except (aiohttp.ClientError, asyncio.TimeoutError) as e:
            raise AmpError(f"the amplifier did not answer ({type(e).__name__})") from e
        for block in re.findall(r"<service>(.*?)</service>", desc, re.S):
            if SERVICE in block:
                path = re.search(r"<controlURL>\s*([^<]+?)\s*</controlURL>", block)
                if path:
                    self._control_url = urljoin(self.description_url, path.group(1))
                    return self._control_url
        raise AmpError("the amplifier has no RenderingControl service")

    async def call(self, action, args=()):
        """Send one RenderingControl action and return the answer's text. Raises AmpError on no answer,
        an HTTP error or a SOAP fault: a request is only a success when the amplifier says so."""
        url = await self.control_url()
        body = _soap_body(action, [("InstanceID", 0), ("Channel", "Master"), *args])
        headers = {"Content-Type": 'text/xml; charset="utf-8"', "SOAPACTION": f'"{SERVICE}#{action}"'}
        s = await self._http()
        try:
            async with s.post(url, data=body.encode(), headers=headers) as r:
                text = await r.text()
                status = r.status
        except (aiohttp.ClientError, asyncio.TimeoutError) as e:
            raise AmpError(f"the amplifier did not answer {action} ({type(e).__name__})") from e
        fault = _fault(text)
        if fault:
            raise AmpError(f"the amplifier refused {action}: {fault}")
        if status != 200:
            raise AmpError(f"the amplifier answered {action} with HTTP {status}")
        if f"{action}Response" not in text:
            raise AmpError(f"the amplifier's answer to {action} was not understood")
        return text

    # ---------- volume, mute, balance ----------
    async def get_volume(self):
        return int(_out(await self.call("GetVolume"), "CurrentVolume"))

    async def set_volume(self, level):
        level = max(0, min(int(level), self.max_volume))
        async with self._lock:
            await self.call("SetVolume", [("DesiredVolume", level)])
            self._level = level
        self._changed()
        return level

    async def get_mute(self):
        return _out(await self.call("GetMute"), "CurrentMute").strip().lower() in ("1", "true", "yes")

    async def set_mute(self, on):
        async with self._lock:
            await self.call("SetMute", [("DesiredMute", 1 if on else 0)])
            self._muted = bool(on)
        self._changed()

    async def get_balance(self):
        return int(_out(await self.call("X_GetBalance"), "CurrentBalance"))

    async def set_balance(self, value):
        """0 to 100 on the PMA-900HNE: lower is left, higher right, 50 the middle."""
        value = max(0, min(int(value), 100))
        await self.call("X_SetBalance", [("DesiredBalance", value)])
        return value

    async def step_volume(self, direction, steps=1):
        """Up (+1) or down (-1) by `steps` presses. Reads the level first when it is not known or old."""
        if self._level is None or time.monotonic() - self._read_at > 5:
            await self.read()
        return await self.set_volume((self._level or 0) + direction * self.step * max(1, steps))

    async def toggle_mute(self):
        if self._muted is None:
            await self.read()
        await self.set_mute(not self._muted)

    async def read(self):
        """Read volume and mute from the amplifier. Raises AmpError when it does not answer."""
        level, muted = await self.get_volume(), await self.get_mute()
        changed = (level, muted) != (self._level, self._muted)
        self._level, self._muted, self._read_at = level, muted, time.monotonic()
        if changed:
            self._changed()

    def refresh_soon(self, max_age=10):
        """Read again in the background if the last read is old (someone turned the knob on the
        amplifier). Never raises: an amplifier that is off just shows no level."""
        if time.monotonic() - self._read_at < max_age or (self._refreshing and not self._refreshing.done()):
            return
        self._read_at = time.monotonic()   # no retry storm while it is off

        async def go():
            try:
                await self.read()
            except AmpError as e:
                log.debug("amplifier read failed: %s", e)
                if self._level is not None:
                    self._level = self._muted = None
                    self._changed()
        try:
            self._refreshing = asyncio.get_running_loop().create_task(go())
        except RuntimeError:
            pass

    async def close(self):
        if self._session and not self._session.closed:
            await self._session.close()


def from_config(cfg):
    """The amplifier for one TV's "amplifier" block, or None."""
    if not isinstance(cfg, dict) or cfg.get("enabled") is False:
        return None
    kind = cfg.get("kind", "upnp")
    if kind != "upnp":
        raise ValueError(f'amplifier: unknown kind "{kind}" (only "upnp" so far)')
    return UpnpAmp(cfg)
