package com.mairwunnx.worldapi.routes

import com.mairwunnx.worldapi.api.Endpoint
import com.mairwunnx.worldapi.api.EndpointGroup
import com.mairwunnx.worldapi.api.Scope
import com.mairwunnx.worldapi.api.endpointsOf
import com.mairwunnx.worldapi.core.ModApi

/** Endpoints whose handlers read the game's server through [ModApi]. */
fun endpoints(tag: String, read: Scope, block: EndpointGroup<ModApi>.() -> Unit): List<Endpoint<ModApi>> = endpointsOf(tag, read, block)
