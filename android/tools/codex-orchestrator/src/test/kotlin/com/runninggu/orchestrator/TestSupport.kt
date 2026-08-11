package com.runninggu.orchestrator

import com.runninggu.orchestrator.logging.StructuredLogger
import com.runninggu.orchestrator.rpc.CodexAppServerClient
import java.nio.file.Path

fun mockClient(temp: Path): CodexAppServerClient {
    val javaExecutable = Path.of(
        System.getProperty("java.home"),
        "bin",
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "java.exe" else "java",
    ).toString()
    val command = listOf(
        javaExecutable,
        "-cp",
        System.getProperty("java.class.path"),
        MockAppServerMain::class.java.name,
    )
    return CodexAppServerClient(command, StructuredLogger(temp.resolve("mock.jsonl"), quiet = true))
}

fun javaExecutable(): String = Path.of(
    System.getProperty("java.home"),
    "bin",
    if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "java.exe" else "java",
).toString()
