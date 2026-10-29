package io.github.kkwans.nasfilebrowser.ui

import com.google.zxing.BarcodeFormat
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Test

class TvQrDecoderTest {
    @Test fun cameraSensorFramesDecodeInAllFourOrientations() {
        val invitation="fileway://tv/pair?data=owned-camera-orientation-regression"
        val qr=QRCodeWriter().encode(invitation,BarcodeFormat.QR_CODE,220,220)
        var width=480;var height=360
        var frame=ByteArray(width*height){255.toByte()}
        for(y in 0 until qr.height)for(x in 0 until qr.width)
            frame[(y+60)*width+x+90]=if(qr[x,y])0 else 255.toByte()
        val reader=MultiFormatReader().apply{setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))}
        repeat(4){quarterTurns->
            try{assertEquals("Sensor orientation ${quarterTurns*90}",invitation,decodeTvQrLuma(frame,width,height,reader))}
            finally{reader.reset()}
            val rotated=ByteArray(frame.size)
            for(y in 0 until height)for(x in 0 until width)rotated[x*height+(height-1-y)]=frame[y*width+x]
            frame=rotated;val previousWidth=width;width=height;height=previousWidth
        }
    }
}
