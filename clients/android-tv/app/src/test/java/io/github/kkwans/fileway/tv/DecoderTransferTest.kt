package io.github.kkwans.fileway.tv

import androidx.media3.common.C
import io.github.kkwans.nasfilebrowser.player.*
import org.junit.Assert.*
import org.junit.Test

class DecoderTransferTest {
    @Test fun nullableFormatMetadataRoundTripsWithoutBecomingTheStringNull() {
        val key=DecoderTrackIdentity(C.TRACK_TYPE_AUDIO,"2",null,"zh-Hans","audio/mp4a-latm",null,0,1,6,48000,-1)
        assertEquals(key,decoderTransferTrack(key.transferJson(),C.TRACK_TYPE_AUDIO))
        val subtitle=key.copy(type=C.TRACK_TYPE_TEXT,id=null,label="双语 · 特效",mime="application/x-ssa",channels=-1,sampleRate=-1)
        assertEquals(subtitle,decoderTransferTrack(subtitle.transferJson(),C.TRACK_TYPE_TEXT))
    }
    @Test fun wrongTrackTypeAndUnboundedMetadataAreRejected() {
        val key=DecoderTrackIdentity(C.TRACK_TYPE_AUDIO,"2",null,null,"audio/ac3",null,0,0,2,48000,-1)
        try{decoderTransferTrack(key.transferJson(),C.TRACK_TYPE_TEXT);fail("Type mismatch accepted")}catch(_:IllegalArgumentException){}
        try{decoderTransferTrack(key.transferJson().put("label","x".repeat(1025)),C.TRACK_TYPE_AUDIO);fail("Unbounded label accepted")}catch(_:IllegalArgumentException){}
    }
}
