package com.sinayazdan.tvvpn

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.UUID

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var activeProfileText: TextView
    private lateinit var connectButton: Button
    private lateinit var emptyText: TextView
    private lateinit var profileList: RecyclerView
    private lateinit var adapter: ProfileAdapter

    private var profiles: MutableList<Profile> = mutableListOf()

    companion object {
        private const val REQUEST_VPN = 1001
        private const val REQUEST_FILE = 1002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        activeProfileText = findViewById(R.id.activeProfileText)
        connectButton = findViewById(R.id.connectButton)
        emptyText = findViewById(R.id.emptyText)
        profileList = findViewById(R.id.profileList)

        adapter = ProfileAdapter { profile ->
            ProfileStore.setSelected(this, profile.id)
            refreshProfiles()
        }
        profileList.layoutManager = LinearLayoutManager(this)
        profileList.adapter = adapter

        connectButton.setOnClickListener { onConnectClicked() }
        findViewById<Button>(R.id.addButton).setOnClickListener { showAddDialog() }
        findViewById<Button>(R.id.importFileButton).setOnClickListener { openFilePicker() }
        findViewById<Button>(R.id.deleteButton).setOnClickListener { deleteSelected() }

        BoxVpnService.stateListener = { runOnUiThread { refreshStatus() } }

        connectButton.requestFocus()
        refreshProfiles()
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshProfiles()
        refreshStatus()
    }

    override fun onDestroy() {
        if (BoxVpnService.stateListener != null) BoxVpnService.stateListener = null
        super.onDestroy()
    }

    // ---------------- UI state ----------------

    private fun refreshProfiles() {
        profiles = ProfileStore.list(this)
        adapter.submit(profiles, ProfileStore.selectedId(this))
        emptyText.visibility = if (profiles.isEmpty()) View.VISIBLE else View.GONE
        profileList.visibility = if (profiles.isEmpty()) View.GONE else View.VISIBLE
        val selected = ProfileStore.selected(this)
        activeProfileText.text = when {
            selected == null -> ""
            BoxVpnService.state == BoxVpnService.State.Started ||
                BoxVpnService.state == BoxVpnService.State.Starting ->
                "کانفیگ فعال: ${selected.name}"
            else -> "کانفیگ انتخاب‌شده: ${selected.name}"
        }
    }

    private fun refreshStatus() {
        when (BoxVpnService.state) {
            BoxVpnService.State.Started -> {
                statusText.text = "● متصل"
                statusText.setTextColor(0xFF4ADE80.toInt())
                connectButton.text = "قطع اتصال"
            }
            BoxVpnService.State.Starting -> {
                statusText.text = "● در حال اتصال…"
                statusText.setTextColor(0xFFFACC15.toInt())
                connectButton.text = "قطع اتصال"
            }
            BoxVpnService.State.Error -> {
                statusText.text = "● خطا"
                statusText.setTextColor(0xFFF87171.toInt())
                connectButton.text = "اتصال"
                BoxVpnService.lastError?.let {
                    Toast.makeText(this, "خطا: $it", Toast.LENGTH_LONG).show()
                }
            }
            BoxVpnService.State.Stopped -> {
                statusText.text = "● قطع"
                statusText.setTextColor(0xFFF87171.toInt())
                connectButton.text = "اتصال"
            }
        }
        refreshProfiles()
    }

    // ---------------- actions ----------------

    private fun onConnectClicked() {
        when (BoxVpnService.state) {
            BoxVpnService.State.Started, BoxVpnService.State.Starting -> {
                startService(Intent(this, BoxVpnService::class.java).apply {
                    action = BoxVpnService.ACTION_STOP
                })
            }
            else -> {
                if (ProfileStore.selected(this) == null) {
                    Toast.makeText(this, "اول یه کانفیگ اضافه و انتخاب کن", Toast.LENGTH_LONG).show()
                    return
                }
                val prepareIntent = VpnService.prepare(this)
                if (prepareIntent != null) {
                    startActivityForResult(prepareIntent, REQUEST_VPN)
                } else {
                    startVpn()
                }
            }
        }
    }

    private fun startVpn() {
        val intent = Intent(this, BoxVpnService::class.java).apply {
            action = BoxVpnService.ACTION_START
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun showAddDialog() {
        val input = EditText(this).apply {
            hint = "لینک کانفیگ رو این‌جا پیست کن"
            setTextColor(0xFF111827.toInt())
            setHintTextColor(0xFF6B7280.toInt())
            minLines = 2
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
            addView(input)
            addView(Button(context).apply {
                text = "پیست از کلیپ‌بورد"
                setOnClickListener {
                    val clipboard = getSystemService(ClipboardManager::class.java)
                    val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                    if (!text.isNullOrBlank()) input.setText(text)
                }
            })
        }
        AlertDialog.Builder(this)
            .setTitle("افزودن کانفیگ")
            .setView(container)
            .setPositiveButton("ذخیره") { _, _ ->
                val raw = input.text.toString().trim()
                if (raw.isNotBlank()) importLinks(raw)
            }
            .setNegativeButton("لغو", null)
            .show()
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        runCatching { startActivityForResult(intent, REQUEST_FILE) }
            .onFailure { Toast.makeText(this, "مدیر فایل پیدا نشد", Toast.LENGTH_LONG).show() }
    }

    private fun deleteSelected() {
        val selected = ProfileStore.selected(this) ?: run {
            Toast.makeText(this, "چیزی انتخاب نشده", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("حذف کانفیگ")
            .setMessage("«${selected.name}» حذف بشه؟")
            .setPositiveButton("حذف") { _, _ ->
                if (BoxVpnService.state == BoxVpnService.State.Started ||
                    BoxVpnService.state == BoxVpnService.State.Starting
                ) {
                    startService(Intent(this, BoxVpnService::class.java).apply {
                        action = BoxVpnService.ACTION_STOP
                    })
                }
                ProfileStore.delete(this, selected.id)
                refreshProfiles()
            }
            .setNegativeButton("لغو", null)
            .show()
    }

    private fun importLinks(text: String) {
        val lines = text.split("\n").map { it.trim() }.filter { it.isNotBlank() }
        var added = 0
        var errors = 0
        var sawOpenVpn = false
        for (line in lines) {
            if (line.contains("client") && line.contains("dev tun") || line.endsWith(".ovpn")) {
                sawOpenVpn = true
                continue
            }
            try {
                val parsed = LinkParser.parse(line)
                ProfileStore.add(
                    this,
                    Profile(
                        id = UUID.randomUUID().toString(),
                        name = parsed.name,
                        protocol = parsed.protocol,
                        raw = line,
                    ),
                )
                added++
            } catch (e: Exception) {
                errors++
            }
        }
        refreshProfiles()
        when {
            added > 0 -> Toast.makeText(this, "$added کانفیگ اضافه شد", Toast.LENGTH_SHORT).show()
            sawOpenVpn -> Toast.makeText(
                this,
                "پشتیبانی از OpenVPN تو فاز بعدی اضافه می‌شه",
                Toast.LENGTH_LONG,
            ).show()
            else -> Toast.makeText(this, "لینک معتبری پیدا نشد", Toast.LENGTH_LONG).show()
        }
        if (errors > 0 && added > 0) {
            Toast.makeText(this, "$errors لینک نامعتبر بود", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_VPN -> {
                if (resultCode == RESULT_OK) startVpn()
                else Toast.makeText(this, "مجوز VPN داده نشد", Toast.LENGTH_LONG).show()
            }
            REQUEST_FILE -> {
                if (resultCode == RESULT_OK) {
                    val uri = data?.data ?: return
                    val text = runCatching {
                        contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                    }.getOrNull()
                    if (text.isNullOrBlank()) {
                        Toast.makeText(this, "فایل خالی یا ناخوانا بود", Toast.LENGTH_LONG).show()
                    } else if (text.contains("dev tun") || text.contains("remote ")) {
                        Toast.makeText(
                            this,
                            "پشتیبانی از OpenVPN تو فاز بعدی اضافه می‌شه",
                            Toast.LENGTH_LONG,
                        ).show()
                    } else {
                        importLinks(text)
                    }
                }
            }
        }
    }

    // ---------------- adapter ----------------

    class ProfileAdapter(private val onClick: (Profile) -> Unit) :
        RecyclerView.Adapter<ProfileAdapter.Holder>() {

        private var items: List<Profile> = emptyList()
        private var selectedId: String? = null

        fun submit(list: List<Profile>, selected: String?) {
            items = list
            selectedId = selected
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_profile, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val profile = items[position]
            holder.name.text = profile.name
            holder.badge.text = profile.protocol
            holder.detail.text = try {
                LinkParser.parse(profile.raw).endpoint
            } catch (e: Exception) {
                ""
            }
            holder.itemView.isSelected = profile.id == selectedId
            holder.itemView.setOnClickListener { onClick(profile) }
        }

        override fun getItemCount(): Int = items.size

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.profileName)
            val detail: TextView = view.findViewById(R.id.profileDetail)
            val badge: TextView = view.findViewById(R.id.profileBadge)
        }
    }
}
