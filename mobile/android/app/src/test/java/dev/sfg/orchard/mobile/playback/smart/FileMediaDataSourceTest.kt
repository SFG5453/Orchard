/*
 * Copyright (C) 2026 SFG545
 *
 * This file is part of Orchard.
 *
 * Orchard is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * Orchard is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

package dev.sfg.orchard.mobile.playback.smart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileMediaDataSourceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun readsDataAccurately() {
        val file = tempFolder.newFile("sample.bin")
        val sampleData = byteArrayOf(10, 20, 30, 40, 50, 60, 70, 80)
        file.writeBytes(sampleData)

        val dataSource = FileMediaDataSource(file)
        assertEquals(8L, dataSource.size)

        val buffer = ByteArray(4)
        val bytesRead = dataSource.readAt(2, buffer, 0, 4)
        assertEquals(4, bytesRead)
        assertEquals(30.toByte(), buffer[0])
        assertEquals(40.toByte(), buffer[1])
        assertEquals(50.toByte(), buffer[2])
        assertEquals(60.toByte(), buffer[3])

        val eofRead = dataSource.readAt(8, buffer, 0, 4)
        assertEquals(-1, eofRead)

        dataSource.close()
    }

    @Test
    fun handlesZeroSizeRead() {
        val file = tempFolder.newFile("empty.bin")
        file.writeBytes(byteArrayOf(1, 2, 3))
        val dataSource = FileMediaDataSource(file)

        val buffer = ByteArray(4)
        val bytesRead = dataSource.readAt(0, buffer, 0, 0)
        assertEquals(0, bytesRead)

        dataSource.close()
    }
}
