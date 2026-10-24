package com.mairwunnx.worldapi.routes

import com.mairwunnx.worldapi.api.Endpoint
import com.mairwunnx.worldapi.api.EndpointGroup
import com.mairwunnx.worldapi.api.Scope
import com.mairwunnx.worldapi.api.endpointsOf
import com.mairwunnx.worldapi.core.PaperApi

/** Endpoints whose handlers read the Bukkit server through [PaperApi]. */
fun endpoints(tag: String, read: Scope, block: EndpointGroup<PaperApi>.() -> Unit): List<Endpoint<PaperApi>> = endpointsOf(tag, read, block)
