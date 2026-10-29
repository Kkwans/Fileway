package io.github.kkwans.nasfilebrowser.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The system picker grants only this image; no photo-library permission or
 * persistent copy is needed. Bound both compressed input and decoded pixels. */
internal suspend fun tvQrFromImage(context:Context,uri:Uri):String=withContext(Dispatchers.Default){
    val bytes=context.contentResolver.openInputStream(uri)?.use{input->
        val output=ByteArrayOutputStream();val buffer=ByteArray(16384)
        while(true){val read=input.read(buffer);if(read<0)break;require(output.size()+read<=16*1024*1024){"图片过大，请选择二维码截图"};output.write(buffer,0,read)}
        output.toByteArray()
    }?:error("无法读取二维码图片")
    val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
    BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
    require(bounds.outWidth>0&&bounds.outHeight>0){"图片无法解码"}
    var sample=1;while(bounds.outWidth/sample>1600||bounds.outHeight/sample>1600)sample*=2
    val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply{inSampleSize=sample})?:error("图片无法解码")
    try{
        val pixels=IntArray(bitmap.width*bitmap.height);bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
        val source=RGBLuminanceSource(bitmap.width,bitmap.height,pixels)
        val text=try{MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)),mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),DecodeHintType.TRY_HARDER to true)).text}
            catch(_:ReaderException){error("未识别到栖卷 TV 二维码，请选择清晰截图")}
        require(text.startsWith("fileway://tv/pair?")){"这不是栖卷 TV 配对码"};text
    }finally{bitmap.recycle()}
}
