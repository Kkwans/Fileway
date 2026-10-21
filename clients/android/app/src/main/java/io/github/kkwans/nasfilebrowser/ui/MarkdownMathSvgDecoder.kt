package io.github.kkwans.nasfilebrowser.ui

import coil3.ImageLoader
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.svg.SvgDecoder
import coil3.svg.SvgImage
import com.caverock.androidsvg.RenderOptions

/** Keep vector math inside its own viewport, not the Activity's native Canvas.
 * Coil 3.6.3 normalizes SVG width/height to 100%, while its deferred SvgImage
 * draws directly onto the supplied canvas without setting a viewport.
 */
internal object MarkdownMathSvgDecoderFactory : Decoder.Factory {
    private val delegate = SvgDecoder.Factory(useViewBoundsAsIntrinsicSize = false, renderToBitmap = false)

    override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
        val decoder = delegate.create(result, options, imageLoader) ?: return null
        return Decoder {
            val decoded = decoder.decode() ?: return@Decoder null
            val image = decoded.image as? SvgImage ?: return@Decoder decoded
            val viewport = (image.renderOptions ?: RenderOptions()).viewPort(
                0f, 0f, image.width.toFloat(), image.height.toFloat(),
            )
            DecodeResult(SvgImage(image.svg, viewport, image.width, image.height), decoded.isSampled)
        }
    }
}
