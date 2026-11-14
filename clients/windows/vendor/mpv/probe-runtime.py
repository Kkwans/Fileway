"""Inspect the isolated pinned runtime; prints only public binary metadata."""
import argparse
import ctypes
import json
import pathlib

parser = argparse.ArgumentParser()
parser.add_argument("runtime_directory")
arguments = parser.parse_args()
root = pathlib.Path(arguments.runtime_directory).resolve()
mpv = ctypes.CDLL(str(root / "libmpv-2.dll"), winmode=0x900)
mpv.mpv_client_api_version.restype = ctypes.c_uint
mpv.mpv_create.restype = ctypes.c_void_p
mpv.mpv_set_option_string.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_char_p]
mpv.mpv_initialize.argtypes = [ctypes.c_void_p]
mpv.mpv_get_property_string.argtypes = [ctypes.c_void_p, ctypes.c_char_p]
mpv.mpv_get_property_string.restype = ctypes.c_void_p
mpv.mpv_free.argtypes = [ctypes.c_void_p]
mpv.mpv_terminate_destroy.argtypes = [ctypes.c_void_p]
handle = mpv.mpv_create()
if not handle:
    raise RuntimeError("mpv_create failed")
try:
    for key, value in [("config", "no"), ("terminal", "no"), ("msg-level", "all=no"), ("load-scripts", "no"),
                       ("scripts", ""), ("ytdl", "no"), ("osc", "no"), ("load-stats-overlay", "no"),
                       ("input-default-bindings", "no"), ("input-builtin-bindings", "no"), ("input-vo-keyboard", "no"),
                       ("input-cursor", "no"), ("access-references", "no"), ("autoload-files", "no"),
                       ("load-unsafe-playlists", "no"), ("idle", "yes"), ("keep-open", "yes"),
                       ("title", "Fileway video"), ("force-media-title", "Fileway video"),
                       ("vo", "gpu-next"), ("gpu-context", "d3d11"), ("d3d11-output-mode", "window"),
                       ("hwdec", "auto-safe"), ("audio-client-name", "Fileway"), ("network-timeout", "15")]:
        result = mpv.mpv_set_option_string(handle, key.encode(), value.encode())
        if result < 0:
            raise RuntimeError("option " + key + " failed: " + str(result))
    if mpv.mpv_initialize(handle) < 0:
        raise RuntimeError("mpv_initialize failed")
    observed = {"apiVersion": mpv.mpv_client_api_version()}
    for key in ["mpv-version", "mpv-configuration", "ffmpeg-version", "libass-version"]:
        pointer = mpv.mpv_get_property_string(handle, key.encode())
        observed[key] = ctypes.string_at(pointer).decode() if pointer else None
        if pointer:
            mpv.mpv_free(pointer)
    avcodec = ctypes.CDLL(str(next(root.glob("avcodec-*.dll"))), winmode=0x900)
    avcodec.avcodec_configuration.restype = ctypes.c_char_p
    avcodec.avcodec_license.restype = ctypes.c_char_p
    observed["avcodecConfiguration"] = avcodec.avcodec_configuration().decode()
    observed["avcodecLicense"] = avcodec.avcodec_license().decode()
    observed["nonfreeEnabled"] = "--enable-nonfree" in observed["avcodecConfiguration"]
    observed["gplEnabled"] = "--enable-gpl" in observed["avcodecConfiguration"]
    observed["version3Enabled"] = "--enable-version3" in observed["avcodecConfiguration"]
    observed["redistributionApproved"] = False
    if observed["nonfreeEnabled"]:
        raise RuntimeError("nonfree is enabled in the actual FFmpeg binary")
    (root / "binary-provenance.json").write_text(json.dumps(observed, indent=2), encoding="utf-8")
    print(json.dumps({key: value for key, value in observed.items() if key not in ["mpv-configuration", "avcodecConfiguration"]}))
finally:
    mpv.mpv_terminate_destroy(handle)
