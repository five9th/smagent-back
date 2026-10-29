package com.five9th.smagent

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class SmagentApplication

fun main(args: Array<String>) {
    runApplication<SmagentApplication>(*args)
}
