package io.github.loshine.svg.example

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform
