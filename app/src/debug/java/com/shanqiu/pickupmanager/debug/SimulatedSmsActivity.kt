/*
 * 码上取 PickupManager —— 完全离线的安卓快递取件助手
 * Copyright (C) 2026 shanqiugzs
 * SPDX-License-Identifier: GPL-3.0-or-later
 * 许可全文见仓库根目录 LICENSE。
 */

package com.shanqiu.pickupmanager.debug

import android.content.Intent
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.shanqiu.pickupmanager.data.DataChangeContentProvider
import com.shanqiu.pickupmanager.data.db.PickupDatabase
import com.shanqiu.pickupmanager.data.repository.PickupRepository
import com.shanqiu.pickupmanager.service.AddressMatcher
import com.shanqiu.pickupmanager.ui.ComposeMainActivity
import com.shanqiu.pickupmanager.util.PickupIngest
import com.shanqiu.pickupmanager.widget.WidgetSyncHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「没有真短信也能测试」的调试入口 —— **只在 debug 变体编译**。
 *
 * ## 为什么这样设计
 *
 * 想在没有收件箱短信的设备上把取件流程跑通，通常有三条路，前两条都不能用：
 *
 * 1. `adb emu sms send` / 真机发短信：依赖设备在线、还得真的往收件箱里塞东西，
 *    调试时没法精确控制正文，也容易污染真实短信。
 * 2. 往 `content://sms` 插入短信：Android 4.4+ 起只有**默认短信应用**能写短信，
 *    本应用不是默认短信应用，也不该为了测试去申请这个身份。
 *    `adb shell am broadcast` 伪造 SMS_RECEIVED 同理：那是系统保护广播，
 *    还要手工构造 PDU，属于把测试成本花在跟业务无关的地方。
 *
 * 于是走第三条：**绕开系统短信，直接把正文喂给业务入口**。
 *
 * 这里刻意不新写一份解析器，而是复用 [PickupIngest.parse]，再按
 * `SmsReceiver.saveItems` 完全相同的顺序做留档检查 → 地址归一 → 去重写库 →
 * 常用地址 → 小部件刷新 → 跨进程通知。因此「模拟短信」和「真实短信」
 * 走的是同一条解析与入库链路，差别只在于正文的来源。
 *
 * ## 隔离性
 *
 * 文件位于 `app/src/debug`，图标由 `app/src/debug/AndroidManifest.xml` 合并，
 * release 变体既不编译这段代码，清单里也没有第二个入口；
 * `app/src/main` 一行未改，README 的源文件数 / 中文串 / 单测数声明不受影响。
 *
 * 夹具短信全部是虚构文案：发件人用仓库约定的占位号段，地址不含真实地名，
 * 不写入系统收件箱，也不需要 READ_SMS 权限。
 */
class SimulatedSmsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 允许通过 adb 在运行时注入任意短信正文（含中文）。用 base64 传值是为了绕开
        // shell 引号 / 编码差异；注入的内容只存在于本次 Intent，不写进仓库、不落到磁盘。
        // 例：adb shell am start -n com.shanqiu.pickupmanager/.debug.SimulatedSmsActivity \
        //       --es sms_body_b64 '<base64>' --ez sms_auto_insert true --ez sms_clear true
        val injectedBody = decodeExtra("sms_body_b64") ?: intent.getStringExtra("sms_body")
        val injectedSender = decodeExtra("sms_sender_b64")
            ?: intent.getStringExtra("sms_sender")
            ?: FIXTURE_SENDER
        // 批量注入：一次 adb 命令按序跑完一整批短信，避免逐条启动时
        // 上一条的异步入库协程被下一次启动打断。每行一条，空行忽略。
        val batch = decodeExtra("sms_batch_b64")
            ?.lines()
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()
        val bodies = batch.ifEmpty { listOfNotNull(injectedBody) }
        val autoInsert = intent.getBooleanExtra("sms_auto_insert", false)
        val clearFirst = intent.getBooleanExtra("sms_clear", false)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SimulatedSmsScreen(
                        initialBody = bodies.firstOrNull() ?: TEMPLATES[0].body,
                        initialSender = injectedSender,
                        autoInsert = autoInsert && bodies.isNotEmpty(),
                        onInsert = { body, sender, done ->
                            insertAll(bodies.ifEmpty { listOf(body) }, sender, clearFirst, done)
                        },
                        onClear = { done -> clear(done) },
                        onOpenApp = { openMain() }
                    )
                }
            }
        }
    }

    private fun decodeExtra(key: String): String? {
        val raw = intent.getStringExtra(key) ?: return null
        return try {
            String(Base64.decode(raw, Base64.DEFAULT), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun openMain() {
        startActivity(Intent(this, ComposeMainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        })
    }

    private fun insertAll(
        bodies: List<String>,
        sender: String,
        clearFirst: Boolean,
        done: (String) -> Unit
    ) {
        lifecycleScope.launch {
            val message = withContext(Dispatchers.IO) {
                // 同一个取件码 6 小时内只入库一次。带 sms_clear 时先走应用自己的
                // deleteAll()，让重复注入同一批短信也能拿到一份全新的解析结果。
                // 部分厂商 ROM 不允许 shell 执行 pm clear，这是不依赖系统权限的替代路径。
                if (clearFirst) {
                    PickupRepository.getInstance(applicationContext).deleteAll()
                }
                buildString {
                    bodies.forEachIndexed { index, body ->
                        if (index > 0) append('\n')
                        append("[${index + 1}/${bodies.size}] ")
                        append(ingest(body, sender).replace('\n', ' '))
                    }
                }
            }
            done(message)
        }
    }

    private fun clear(done: (String) -> Unit) {
        lifecycleScope.launch {
            val message = withContext(Dispatchers.IO) {
                PickupRepository.getInstance(applicationContext).deleteAll()
                "已清空取件记录、已删留档、保存地址与全部偏好设置；可以重新插入夹具短信了。"
            }
            done(message)
        }
    }

    /** 与 SmsReceiver.saveItems 同构的入库链路；返回给界面看的纯文本结果。 */
    private suspend fun ingest(body: String, sender: String): String = withContext(Dispatchers.IO) {
        val appContext = applicationContext
        val outcome = PickupIngest.parse(body, sender, System.currentTimeMillis())

        if (outcome.items.isEmpty()) {
            return@withContext if (outcome.hasNoCode) {
                "识别为「无取件码」取件短信（引擎 ${outcome.engine}），按设计不入库。"
            } else {
                "未提取到取件码（引擎 ${outcome.engine}），没有写入任何数据。"
            }
        }

        val database = PickupDatabase.getDatabase(appContext)
        val dao = database.pickupItemDao()
        val deletedDao = database.deletedPickupItemDao()
        val savedAddressDao = database.savedAddressDao()
        val matcher = AddressMatcher(dao, savedAddressDao)
        matcher.initialize()

        var saved = 0
        val codes = mutableListOf<String>()
        for (raw in outcome.items) {
            // 与真实短信一致：用户删过的记录不许复活
            if (deletedDao.isDeleted(raw.pickupCode, raw.receiveTime)) continue

            val match = matcher.matchAndPredictAddress(
                raw.address, raw.senderPhone, raw.courierCompany
            )
            val item = raw.copy(
                address = match.standardizedAddress,
                addressTag = match.addressTag
            )
            if (dao.insertOrUpdateItem(item)) {
                saved++
                codes.add(item.pickupCode)
                if (item.addressTag.isNotBlank()) {
                    savedAddressDao.insertOrUpdateAddress(
                        item.addressTag, match.standardizedAddress
                    )
                }
            }
        }

        // 与真实短信一致：刷新小部件，并给主进程的 ContentObserver 一个信标
        WidgetSyncHelper.notifyWidgetDataChanged(appContext)
        appContext.contentResolver.notifyChange(DataChangeContentProvider.CONTENT_URI, null)

        val head = "解析引擎：${outcome.engine}\n" +
            "解析出 ${outcome.items.size} 个取件码，实际入库 $saved 条\n" +
            "取件码：${codes.joinToString("、").ifEmpty { "无" }}\n"
        head + if (saved == 0) {
            "全部被 6 小时去重窗口或已删留档拦截。重复点同一模板不会新增卡片，" +
                "需要重来请点下方「清空测试数据」。"
        } else {
            "列表与桌面小部件已收到刷新通知。"
        }
    }
}

/** 预设夹具。号码是仓库统一占位号段，地址是虚构驿站，不含任何真实信息。 */
private data class SmsTemplate(val title: String, val note: String, val body: String)

private const val FIXTURE_SENDER = "1069000000000"

private val TEMPLATES = listOf(
    SmsTemplate(
        "标准驿站码",
        "签名格式 xx-x-xxxx，预期 1 条",
        "【虚构驿站】您的取件码 A2-1-3456，请到虚构驿站A区凭码取件。"
    ),
    SmsTemplate(
        "一条短信多个包裹",
        "验证一短信多码，预期 2 条",
        "【虚构快递】您有 2 个包裹已到虚构驿站B区，取件码 B3-2-1001、B3-2-1002，请及时领取。"
    ),
    SmsTemplate(
        "四位柜码",
        "短码形态，预期 1 条",
        "【虚构柜】您的取件码 8848，请在 24 小时内到虚构快递柜C区取件。"
    ),
    SmsTemplate(
        "全角字符",
        "验证输入规范化与取件码归一，预期 1 条",
        "【虚构驿站】取件码 Ａ２－１－３４５６，请到虚构驿站A区取件。"
    ),
    SmsTemplate(
        "长短信（已拼好分段）",
        "模拟长短信拼接后的完整正文，预期 1 条",
        "【虚构驿站】您有一个包裹已于今日送达虚构驿站D区，取件码 C4-3-2026，" +
            "驿站营业时间为每日 08:00 至 22:00，请携带凭证前往领取，" +
            "如需帮助请在工作时间联系驿站工作人员，感谢您的使用。"
    ),
    SmsTemplate(
        "负例：验证码短信",
        "验证一票否决，预期 0 条",
        "【虚构银行】您的验证码是 88213，5 分钟内有效，请勿告知他人。"
    ),
    SmsTemplate(
        "负例：无取件码",
        "验证「凭姓名领取」不落库，预期 0 条",
        "【虚构驿站】您的包裹已到虚构驿站A区，请报姓名直接领取，无需取件码。"
    )
)

@Composable
private fun SimulatedSmsScreen(
    initialBody: String,
    initialSender: String,
    autoInsert: Boolean,
    onInsert: (String, String, (String) -> Unit) -> Unit,
    onClear: ((String) -> Unit) -> Unit,
    onOpenApp: () -> Unit
) {
    var body by rememberSaveable { mutableStateOf(initialBody) }
    var sender by rememberSaveable { mutableStateOf(initialSender) }
    var result by rememberSaveable {
        mutableStateOf("尚未插入。选一个模板，或直接粘贴任意短信正文。")
    }
    var busy by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var autoFired by rememberSaveable { mutableStateOf(false) }

    // adb 带 sms_auto_insert 启动时自动执行一次，避免每次都要手工点按钮。
    // autoFired 用 rememberSaveable 记账，旋转屏幕不会重复入库。
    if (autoInsert && !autoFired && body.isNotBlank()) {
        LaunchedEffect(Unit) {
            autoFired = true
            busy = true
            result = "正在解析并写入…"
            onInsert(body.trim(), sender.trim()) { message ->
                result = message
                busy = false
                onOpenApp()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("模拟短信", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(
            "仅调试包可见。不读也不写系统短信收件箱，不需要短信权限：" +
                "正文直接送入 PickupIngest，之后走与真实短信完全相同的解析、归一、" +
                "地址匹配、去重、入库和小部件刷新链路。",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        HorizontalDivider()

        Text("预设模板", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        TEMPLATES.forEach { template ->
            OutlinedButton(
                onClick = {
                    body = template.body
                    result = "已填入「${template.title}」，点下方按钮写入。"
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(template.title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(
                        template.note,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        HorizontalDivider()

        Text("短信正文（可编辑）", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = body,
            onValueChange = { body = it },
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp),
            maxLines = 8
        )
        OutlinedTextField(
            value = sender,
            onValueChange = { sender = it },
            label = { Text("发件人号码") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = {
                if (body.isBlank() || busy) return@Button
                busy = true
                result = "正在解析并写入…"
                onInsert(body.trim(), sender.trim()) { message ->
                    result = message
                    busy = false
                    onOpenApp()
                }
            },
            enabled = !busy && body.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (busy) "处理中…" else "插入并打开码上取")
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = onOpenApp,
                modifier = Modifier.weight(1f)
            ) { Text("只打开码上取") }
            OutlinedButton(
                onClick = { showClearConfirm = true },
                enabled = !busy,
                modifier = Modifier.weight(1f)
            ) { Text("清空测试数据") }
        }

        Text(
            "提示：同一取件码在 6 小时窗口内只入库一次，连续点同一模板不会新增卡片；" +
                "想反复验证请先清空。清空走的是设置页同一个 deleteAll，会连偏好设置一起重置。" +
                " 也可以从电脑用 adb 注入任意中文短信正文（base64 传入），不必把真实短信写进代码。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        HorizontalDivider()

        Text("本次结果", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                result,
                fontSize = 13.sp,
                modifier = Modifier.padding(12.dp)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清空全部测试数据？") },
            text = { Text("将删除本机全部取件记录、已删留档、保存地址与所有偏好设置，不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    busy = true
                    onClear { message ->
                        result = message
                        busy = false
                    }
                }) { Text("确认清空") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
            }
        )
    }
}
