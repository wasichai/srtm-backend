package srtm.emision

import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder.FontStyle
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder
import com.openhtmltopdf.slf4j.Slf4jLogger
import com.openhtmltopdf.util.XRLog
import org.springframework.stereotype.Component
import org.thymeleaf.TemplateEngine
import org.thymeleaf.context.Context
import org.thymeleaf.templatemode.TemplateMode
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Locale

// a template of templates/emision (thymeleaf, standalone: the app is webflux, there is no mvc view layer) to a pdf with
// openhtmltopdf. the page size (A4) and margins are the template's css: templates/emision/base.css, which every
// template inlines through the "css" variable. the font is DejaVu Sans, embedded (subset) so accents and ñ print
// the same everywhere. thread-safe: the engine caches the parsed templates, the builder is per call
@Component
class PdfRenderer {
    init {
        // openhtmltopdf logs through java.util.logging to stderr by default: through slf4j it follows logging.level
        XRLog.setLoggerImpl(Slf4jLogger())
    }

    private val engine =
        TemplateEngine().apply {
            setTemplateResolver(
                ClassLoaderTemplateResolver().apply {
                    prefix = "templates/emision/"
                    suffix = ".html"
                    templateMode = TemplateMode.HTML
                    characterEncoding = "UTF-8"
                    isCacheable = true
                }
            )
        }

    private val css = recurso("templates/emision/base.css").toString(Charsets.UTF_8)

    private val fuentes =
        listOf(
            Fuente(recurso("fonts/DejaVuSans.ttf"), 400),
            Fuente(recurso("fonts/DejaVuSans-Bold.ttf"), 700)
        )

    // `template` is the file's name under templates/emision, without .html. the model's values are escaped (th:text)
    fun render(
        template: String,
        model: Map<String, Any?>
    ): ByteArray {
        // openhtmltopdf reads xhtml: the templates are well-formed, but prettier writes html5's lowercase doctype
        val html = engine.process(template, Context(ES, model + ("css" to css))).replaceFirst(DOCTYPE, "<!DOCTYPE html>")
        val out = ByteArrayOutputStream()
        PdfRendererBuilder()
            .useFastMode()
            .apply { fuentes.forEach { f -> useFont({ ByteArrayInputStream(f.bytes) }, FAMILIA, f.peso, FontStyle.NORMAL, true) } }
            .withHtmlContent(html, null)
            .toStream(out)
            .run()
        return out.toByteArray()
    }

    private class Fuente(
        val bytes: ByteArray,
        val peso: Int
    )

    private companion object {
        // base.css names it
        const val FAMILIA = "DejaVu Sans"
        val ES: Locale = Locale.forLanguageTag("es-PE")
        val DOCTYPE = Regex("^\\s*<!doctype html>", RegexOption.IGNORE_CASE)

        fun recurso(ruta: String): ByteArray =
            PdfRenderer::class.java.classLoader
                .getResourceAsStream(ruta)
                ?.use { it.readBytes() }
                ?: error("falta el recurso $ruta")
    }
}
