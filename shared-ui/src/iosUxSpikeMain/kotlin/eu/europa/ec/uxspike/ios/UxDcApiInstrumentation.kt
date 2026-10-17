/*
 * Copyright (c) 2026 European Commission
 *
 * Licensed under the EUPL, Version 1.2 or - as soon they will be approved by the European
 * Commission - subsequent versions of the EUPL (the "Licence"); You may not use this work
 * except in compliance with the Licence.
 *
 * You may obtain a copy of the Licence at:
 * https://joinup.ec.europa.eu/software/page/eupl
 *
 * Unless required by applicable law or agreed to in writing, software distributed under
 * the Licence is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF
 * ANY KIND, either express or implied. See the Licence for the specific language
 * governing permissions and limitations under the Licence.
 */

package eu.europa.ec.uxspike.ios

import kotlin.time.TimeSource
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import platform.Foundation.NSDate
import platform.Foundation.NSLog
import platform.Foundation.timeIntervalSince1970
import platform.darwin.mach_msg_type_number_tVar
import platform.darwin.mach_task_self_
import platform.darwin.task_info
import platform.darwin.task_vm_info_data_t

/**
 * D10 instrumentation for the extension run (SPIKE-PLAN M10): the time from the host's creation to its first frame, and
 * the process's memory footprint (`task_info` `phys_footprint`, the number Jetsam judges) at first frame and at
 * completion. Lines go through `NSLog`, so they reach the unified log (Console, `log stream`, `devicectl ... --console`),
 * which `println` does not inside an extension. Read them with the tag [TAG].
 *
 * Line shape: `UX-SPIKE-D10 <event> key=value ...`, events `created`, `first-frame`, `complete`.
 */
internal class UxDcApiMetrics(private val host: String) {
    private val created = TimeSource.Monotonic.markNow()
    private var firstFrameLogged = false
    private var completeLogged = false

    init {
        emit("created", "wall_epoch_ms=${wallEpochMillis()} footprint_mb=${footprintMegabytes()}")
    }

    /** Called once per frame callback; only the first one is logged. */
    fun onFrame() {
        if (firstFrameLogged) return
        firstFrameLogged = true
        emit("first-frame", "start_to_first_frame_ms=${created.elapsedNow().inWholeMilliseconds} " +
            "wall_epoch_ms=${wallEpochMillis()} footprint_mb=${footprintMegabytes()}")
    }

    fun onComplete(outcome: String) {
        if (completeLogged) return
        completeLogged = true
        emit("complete", "outcome=$outcome start_to_complete_ms=${created.elapsedNow().inWholeMilliseconds} " +
            "wall_epoch_ms=${wallEpochMillis()} footprint_mb=${footprintMegabytes()}")
    }

    private fun emit(event: String, fields: String) = NSLog("$TAG $event host=$host $fields")

    private fun wallEpochMillis(): Long = (NSDate().timeIntervalSince1970 * MILLIS_PER_SECOND).toLong()

    companion object {
        const val TAG = "UX-SPIKE-D10"
        private const val MILLIS_PER_SECOND = 1000
        private const val BYTES_PER_MEGABYTE = 1024.0 * 1024.0
        private const val TASK_VM_INFO_FLAVOR = 22u
        private const val INT_BYTES = 4UL

        /** `phys_footprint` of this process in megabytes (one decimal), or -1 when `task_info` fails. */
        @OptIn(ExperimentalForeignApi::class)
        fun footprintMegabytes(): String = memScoped {
            val info = alloc<task_vm_info_data_t>()
            val count = alloc<mach_msg_type_number_tVar>()
            count.value = (sizeOf<task_vm_info_data_t>().toULong() / INT_BYTES).toUInt()
            val rc = task_info(mach_task_self_, TASK_VM_INFO_FLAVOR, info.ptr.reinterpret(), count.ptr)
            if (rc != 0) "-1" else {
                val mb = info.phys_footprint.toDouble() / BYTES_PER_MEGABYTE
                ((mb * 10).toLong() / 10.0).toString()
            }
        }
    }
}
