package com.nnomm

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import com.nnomm.errors.AppError
import io.ktor.http.HttpStatusCode

fun Application.configureRouting() {
    routing {
        get("/") {
            call.respondText("Hello, World!")
        }
        get("/json/kotlinx-serialization") {
            call.respond(mapOf("hello" to "world"))
        }
        
    }
}