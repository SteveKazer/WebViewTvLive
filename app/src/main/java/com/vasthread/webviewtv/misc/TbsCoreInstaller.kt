package com.vasthread.webviewtv.misc

import android.content.Context
import android.os.Build
import java.io.File
import java.util.zip.ZipFile

/**
 * 腾讯 X5 内核本地安装器(v2.4.6)。
 *
 * 背景: X5 官方下载通道近年不稳(设备直接在线自动下载常无进展),
 * 参考开源项目 CCTV_Viewer 的做法: 按设备位数下载内核安装包
 * (本质是 APK), 再用 QbSdk.installLocalTbsCore 一步安装到位,
 * 安装完成后重启应用即走 X5。
 *
 * 位数必须匹配: 32 位内核(v45738)装到 64 位设备会闪退/无限重启,
 * 64 位内核(v46007)装到 32 位设备无法加载 —— 下载按机型自动选择,
 * 手动导入的文件同样做位数校验, 位不对直接拒绝安装。
 *
 * 内核镜像来源为第三方站点(void-tech.cn), 与 CCTV_Viewer 相同;
 * 若站点失效可把内核 apk 放入应用下载目录或用文件选择器导入。
 */
object TbsCoreInstaller {

    /** 32 位内核(v45738, 约 36.5MB) */
    const val VERSION_32 = 45738
    const val URL_32 = "http://void-tech.cn/wp-content/uploads/2024/10/045738_x5.tbs_.apk"
    const val NAME_32 = "045738_x5.tbs_.apk"

    /** 64 位内核(v46007, 约 48.8MB) */
    const val VERSION_64 = 46007
    const val URL_64 = "http://void-tech.cn/wp-content/uploads/2024/10/046007_x5.tbs_.apk"
    const val NAME_64 = "046007_x5.tbs_.apk"

    fun primaryAbi(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Build.SUPPORTED_ABIS.firstOrNull() ?: ""
        } else {
            @Suppress("DEPRECATION")
            Build.CPU_ABI ?: ""
        }

    /** X5 内核只带 ARM 库, x86 模拟器/设备装也无法加载 */
    fun isArmDevice(): Boolean = primaryAbi().contains("arm")

    fun is64BitDevice(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Build.SUPPORTED_ABIS.forEach { if (it.contains("64")) return true }
        }
        return false
    }

    /** 本机适配的内核版本号(非 ARM 设备返回 0, 表示不支持安装) */
    fun targetVersion(): Int = when {
        !isArmDevice() -> 0
        is64BitDevice() -> VERSION_64
        else -> VERSION_32
    }

    fun targetName(): String = if (is64BitDevice()) NAME_64 else NAME_32

    fun targetUrl(): String = if (is64BitDevice()) URL_64 else URL_32

    /** 目标大小(MB), 仅用于界面展示 */
    fun targetSizeMb(): Int = if (is64BitDevice()) 49 else 37

    /** 内核下载/导入目录(应用外置私有目录, 无需存储权限) */
    fun coreDir(context: Context): File? =
        context.getExternalFilesDir("Download")?.apply { mkdirs() }

    fun targetFile(context: Context): File? =
        coreDir(context)?.let { File(it, targetName()) }

    /** 内核包本质是 APK: 能按 zip 打开且含 AndroidManifest 才算完整 */
    fun isCompleteApk(file: File): Boolean = try {
        ZipFile(file).use { it.getEntry("AndroidManifest.xml") != null }
    } catch (e: Exception) {
        false
    }

    /** 内核包里是否有指定位数的 native 库(位数防错装的关键校验) */
    fun apkSupportsDeviceAbi(file: File, bits64: Boolean): Boolean = try {
        ZipFile(file).use { zip ->
            if (bits64) {
                zip.getEntry("lib/arm64-v8a/") != null
            } else {
                zip.getEntry("lib/armeabi-v7a/") != null || zip.getEntry("lib/armeabi/") != null
            }
        }
    } catch (e: Exception) {
        false
    }

    /** 从文件名解析内核版本号(如 045738_x5.tbs_.apk -> 45738) */
    fun parseVersionFromName(name: String): Int? =
        Regex("(\\d{5,6})").find(name)?.groupValues?.get(1)?.toIntOrNull()

    /** 扫描应用下载目录里疑似内核的 apk(供"从本地文件安装"直接列出) */
    fun scanLocalApks(context: Context): List<File> {
        val dir = coreDir(context) ?: return emptyList()
        return dir.listFiles { f ->
            f.isFile && f.length() > 1024 * 1024 &&
                (f.name.contains("x5", true) || f.name.contains(".tbs", true)) &&
                f.extension.equals("apk", true)
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }
}
