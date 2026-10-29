package io.github.kkwans.nasfilebrowser.ui

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** QR detection already handles sensor orientation. PlanarYUVLuminanceSource
 * does not support rotateCounterClockwise; camera rotation is for preview only. */
internal fun decodeTvQrLuma(data:ByteArray,width:Int,height:Int,reader:MultiFormatReader):String {
    val source=PlanarYUVLuminanceSource(data,width,height,0,0,width,height,false)
    return reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
}

/** One analyzer, latest frame only, no image storage or camera on the TV. */
@Composable internal fun TvQrScanner(modifier:Modifier=Modifier,onCode:(String)->Unit,onError:(String)->Unit){
    val context=LocalContext.current;val lifecycle=LocalLifecycleOwner.current
    val preview=remember{PreviewView(context).apply{implementationMode=PreviewView.ImplementationMode.COMPATIBLE}}
    val currentCode by rememberUpdatedState(onCode);val currentError by rememberUpdatedState(onError)
    AndroidView(factory={preview},modifier=modifier)
    DisposableEffect(lifecycle){
        val executor=Executors.newSingleThreadExecutor()
        val active=AtomicBoolean(true);val found=AtomicBoolean(false)
        val providerFuture=ProcessCameraProvider.getInstance(context)
        var ownedPreview:Preview?=null;var ownedAnalysis:ImageAnalysis?=null;var provider:ProcessCameraProvider?=null
        val main=context.mainExecutor
        providerFuture.addListener({
            if(active.get())try{
                val camera=providerFuture.get();provider=camera
                val rendering=Preview.Builder().build().also{it.surfaceProvider=preview.surfaceProvider};ownedPreview=rendering
                val analysis=ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();ownedAnalysis=analysis
                val reader=MultiFormatReader().apply{setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))}
                analysis.setAnalyzer(executor){image->
                    try{
                        if(active.get()&&!found.get()){
                            val plane=image.planes[0];val buffer=plane.buffer.duplicate();val offset=buffer.position()
                            val data=ByteArray(image.width*image.height)
                            for(y in 0 until image.height)for(x in 0 until image.width)data[y*image.width+x]=buffer.get(offset+y*plane.rowStride+x*plane.pixelStride)
                            val text=decodeTvQrLuma(data,image.width,image.height,reader)
                            if(text.startsWith("fileway://tv/pair?")&&found.compareAndSet(false,true))main.execute{if(active.get())currentCode(text)}
                        }
                    }catch(_:NotFoundException){}catch(_:ReaderException){}catch(_:Exception){}
                    finally{reader.reset();image.close()}
                }
                camera.bindToLifecycle(lifecycle,CameraSelector.DEFAULT_BACK_CAMERA,rendering,analysis)
            }catch(_:Exception){currentError("相机暂不可用，请检查权限后重试")}
        },main)
        onDispose{
            active.set(false);ownedAnalysis?.clearAnalyzer()
            val owned=listOfNotNull(ownedPreview,ownedAnalysis).toTypedArray()
            if(owned.isNotEmpty())provider?.unbind(*owned)
            executor.shutdown()
        }
    }
}
