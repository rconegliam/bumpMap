package com.rconegliam.bumpmap.api

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.net.URLClassLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebsiteRoutesTest {
    @Test
    fun servesPublicBundledHtmlAndNestedAssetsWithTheirMediaTypes() {
        val root = checkNotNull(javaClass.classLoader.getResource("website-fixture/"))
        URLClassLoader(arrayOf(root), null).use { fixtureLoader ->
            testApplication {
                environment { classLoader = fixtureLoader }
                application { routing { websiteRoutes() } }

                val index = client.get("/")
                assertEquals(HttpStatusCode.OK, index.status)
                assertEquals(ContentType.Text.Html, index.contentType()?.withoutParameters())
                assertTrue(index.bodyAsText().contains("<title>Map fixture</title>"))

                val css = client.get("/assets/nested/map.css")
                assertEquals(HttpStatusCode.OK, css.status)
                assertEquals(ContentType.Text.CSS, css.contentType()?.withoutParameters())
                assertEquals("body { color: black; }", css.bodyAsText().trim())
                assertEquals("nosniff", css.headers["X-Content-Type-Options"])

                for (path in listOf(
                    "/assets", "/assets/missing.js", "/assets/nested", "/assets/%2e%2e/index.html",
                    "/assets/..%2Findex.html", "/assets/..%5Cindex.html", "/index.html",
                )) {
                    assertEquals(HttpStatusCode.NotFound, client.get(path).status, path)
                }
            }
        }
    }

    @Test
    fun missingBundledWebsiteReturnsNotFound() {
        URLClassLoader(emptyArray(), null).use { emptyLoader ->
            testApplication {
                environment { classLoader = emptyLoader }
                application { routing { websiteRoutes() } }
                assertEquals(HttpStatusCode.NotFound, client.get("/").status)
            }
        }
    }
}
