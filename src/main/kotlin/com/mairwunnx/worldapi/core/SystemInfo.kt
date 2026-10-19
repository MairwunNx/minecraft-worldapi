package com.mairwunnx.worldapi.core

import com.mairwunnx.worldapi.model.BufferPool
import com.mairwunnx.worldapi.model.Collector
import com.mairwunnx.worldapi.model.ContainerLimits
import com.mairwunnx.worldapi.model.Disk
import com.mairwunnx.worldapi.model.HostMemory
import com.mairwunnx.worldapi.model.JavaRuntime
import com.mairwunnx.worldapi.model.Jvm
import com.mairwunnx.worldapi.model.MemoryPool
import com.mairwunnx.worldapi.model.MemoryRegion
import com.mairwunnx.worldapi.model.SystemView
import com.mairwunnx.worldapi.model.Threads
import java.io.File
import java.lang.management.BufferPoolMXBean
import java.lang.management.ManagementFactory
import java.lang.management.MemoryUsage
import java.time.Instant
import kotlin.math.round

/**
 * JVM and host numbers from the JDK's management beans and, on Linux, /proc and the cgroup files.
 * JVM arguments stay out: they can hold passwords. Safe from any thread.
 */
object SystemInfo {
    fun jvm(): Jvm {
        val memory = ManagementFactory.getMemoryMXBean()
        val threads = ManagementFactory.getThreadMXBean()
        return Jvm(
            heap = memory.heapMemoryUsage.region(),
            nonHeap = memory.nonHeapMemoryUsage.region(),
            pools = ManagementFactory.getMemoryPoolMXBeans().filter { it.isValid }.map {
                val usage = it.usage
                MemoryPool(it.name, it.type.name.lowercase(), usage.used, usage.committed, usage.max.takeIf { max -> max >= 0 })
            },
            gc = ManagementFactory.getGarbageCollectorMXBeans().map { Collector(it.name, it.collectionCount.coerceAtLeast(0), it.collectionTime.coerceAtLeast(0)) },
            threads = Threads(threads.threadCount, threads.daemonThreadCount, threads.peakThreadCount),
            classesLoaded = ManagementFactory.getClassLoadingMXBean().loadedClassCount,
            buffers = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean::class.java).map { BufferPool(it.name, it.count, it.memoryUsed, it.totalCapacity) },
        )
    }

    fun system(serverFolder: File): SystemView {
        val os = ManagementFactory.getOperatingSystemMXBean()
        val sun = os as? com.sun.management.OperatingSystemMXBean
        val unix = os as? com.sun.management.UnixOperatingSystemMXBean
        val runtime = ManagementFactory.getRuntimeMXBean()
        val meminfo = procTable("/proc/meminfo")
        val folder = serverFolder.toPath().toAbsolutePath().normalize().toFile()
        return SystemView(
            os = os.name,
            osVersion = os.version,
            arch = os.arch,
            cpus = os.availableProcessors,
            loadAverage = loadAverage() ?: os.systemLoadAverage.takeIf { it >= 0 }?.let { listOf(it.short()) },
            processCpu = sun?.processCpuLoad?.takeIf { it >= 0 }?.short(),
            systemCpu = sun?.cpuLoad?.takeIf { it >= 0 }?.short(),
            memory = HostMemory(
                totalBytes = meminfo["MemTotal"] ?: sun?.totalMemorySize,
                freeBytes = meminfo["MemFree"] ?: sun?.freeMemorySize,
                availableBytes = meminfo["MemAvailable"],
                swapTotalBytes = meminfo["SwapTotal"] ?: sun?.totalSwapSpaceSize,
                swapFreeBytes = meminfo["SwapFree"] ?: sun?.freeSwapSpaceSize,
            ),
            disk = Disk(folder.path, folder.totalSpace, folder.freeSpace, folder.usableSpace),
            container = container(),
            openFiles = unix?.openFileDescriptorCount,
            maxOpenFiles = unix?.maxFileDescriptorCount,
            processResidentBytes = procTable("/proc/self/status")["VmRSS"],
            java = JavaRuntime(runtime.vmName, runtime.vmVendor, Runtime.version().toString(), runtime.uptime / 1000, Instant.ofEpochMilli(runtime.startTime).toString()),
            at = Instant.now().toString(),
        )
    }

    private fun MemoryUsage.region() = MemoryRegion(used, committed, max.takeIf { it >= 0 })

    private fun loadAverage(): List<Double>? = read("/proc/loadavg")?.trim()?.split(' ')?.take(3)?.mapNotNull { it.toDoubleOrNull() }?.takeIf { it.size == 3 }

    /** `Key:   123 kB` lines, in bytes. */
    internal fun procTable(path: String): Map<String, Long> = read(path)?.lineSequence()?.mapNotNull { line ->
        val (key, rest) = line.split(':', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
        val parts = rest.trim().split(Regex("\\s+"))
        val value = parts.firstOrNull()?.toLongOrNull() ?: return@mapNotNull null
        key.trim() to if (parts.getOrNull(1) == "kB") value * 1024 else value
    }?.toMap().orEmpty()

    /** cgroup v2 first, then v1. "max" and v1's huge sentinel both mean no limit. */
    private fun container(): ContainerLimits? {
        val limit = read("/sys/fs/cgroup/memory.max") ?: read("/sys/fs/cgroup/memory/memory.limit_in_bytes")
        val used = read("/sys/fs/cgroup/memory.current") ?: read("/sys/fs/cgroup/memory/memory.usage_in_bytes")
        val cpu = read("/sys/fs/cgroup/cpu.max")?.trim()?.split(' ')?.let { (quota, period) -> quota.toDoubleOrNull()?.div(period.toDouble()) }
            ?: read("/sys/fs/cgroup/cpu/cpu.cfs_quota_us")?.trim()?.toDoubleOrNull()?.takeIf { it > 0 }
                ?.let { quota -> read("/sys/fs/cgroup/cpu/cpu.cfs_period_us")?.trim()?.toDoubleOrNull()?.let { quota / it } }
        if (limit == null && used == null && cpu == null) return null
        return ContainerLimits(
            memoryLimitBytes = limit?.trim()?.toLongOrNull()?.takeIf { it < Long.MAX_VALUE / 2 },
            memoryUsedBytes = used?.trim()?.toLongOrNull(),
            cpuLimit = cpu?.short(),
        )
    }

    private fun read(path: String): String? = runCatching { File(path).takeIf { it.isFile }?.readText() }.getOrNull()

    private fun Double.short() = round(this * 100) / 100
}
