package com.webdollar.miner.ui

import android.content.ComponentName
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import android.os.IBinder
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.webdollar.miner.data.MinerPrefs
import com.webdollar.miner.databinding.ActivityMainBinding
import com.webdollar.miner.mining.MinerStats
import com.webdollar.miner.service.MinerService
import com.webdollar.miner.util.LegacyWalletFormat
import com.webdollar.miner.util.LocalWalletCipher
import com.webdollar.miner.util.WalletBackupCrypto
import com.webdollar.miner.util.WalletBackupPayload
import com.webdollar.miner.util.WalletGenerator
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val vm: MainViewModel by viewModels()

    private var service: MinerService? = null
    private var bound = false
    private var statsJob: Job? = null

    private val importWebdLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: throw IllegalArgumentException("Nu pot citi fișierul")
        }.onSuccess { raw ->
            importLegacyRaw(raw)
        }.onFailure {
            Toast.makeText(this, "Import fișier eșuat: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val b = binder as? MinerService.LocalBinder ?: return
            service = b.getWorkerService()
            bound = true
            observeStats()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            service = null
            statsJob?.cancel()
            statsJob = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUi()
        bindService(Intent(this, MinerService::class.java), conn, Context.BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (bound) unbindService(conn)
        statsJob?.cancel()
    }

    private fun setupUi() {
        // Config defaults
        binding.etPoolUrl.setText(MinerPrefs.poolUrl)
        binding.etPoolKey.setText(MinerPrefs.poolKey)
        binding.etWallet.setText(MinerPrefs.walletAddress)
        binding.swOnlyCharging.isChecked = MinerPrefs.onlyWhenCharging
        refreshWalletBackupPreview()

        binding.btnSave.setOnClickListener {
            vm.setPoolUrl(binding.etPoolUrl.text.toString())
            vm.setPoolKey(binding.etPoolKey.text.toString())
            vm.setWalletAddress(binding.etWallet.text.toString())
            vm.setOnlyWhenCharging(binding.swOnlyCharging.isChecked)
            vm.saveConfig()
            Toast.makeText(this, "Config salvat", Toast.LENGTH_SHORT).show()
        }

        binding.btnGenerateWallet.setOnClickListener {
            val generated = WalletGenerator.generate()
            binding.etWallet.setText(generated.address)

            MinerPrefs.walletAddress = generated.address
            MinerPrefs.walletSecret = generated.secretHex

            vm.setWalletAddress(generated.address)
            refreshWalletBackupPreview()

            Toast.makeText(this, "Wallet generat și salvat automat", Toast.LENGTH_SHORT).show()
        }

        binding.btnCopyAddress.setOnClickListener {
            val address = binding.etWallet.text?.toString().orEmpty()
            if (address.isBlank()) {
                Toast.makeText(this, "Nu există adresă wallet", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            copyToClipboard("wallet_address", address)
            Toast.makeText(this, "Adresa copiată", Toast.LENGTH_SHORT).show()
        }

        binding.btnCopySecret.setOnClickListener {
            val secret = MinerPrefs.walletSecret
            if (secret.isBlank()) {
                Toast.makeText(this, "Nu există secret salvat", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            copyToClipboard("wallet_secret", secret)
            Toast.makeText(this, "Secret copiat. Păstrează-l în siguranță", Toast.LENGTH_LONG).show()
        }

        binding.btnExportEncrypted.setOnClickListener {
            val secret = MinerPrefs.walletSecret
            val address = binding.etWallet.text?.toString().orEmpty().trim()
            if (secret.isBlank() || address.isBlank()) {
                Toast.makeText(this, "Generează sau importă întâi un wallet", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            AlertDialog.Builder(this)
                .setTitle("Export wallet")
                .setItems(arrayOf("Doar .webd (fără criptare)", " .webd + backup criptat (.enc)")) { _, which ->
                    if (which == 0) {
                        runCatching {
                            val dir = getExternalFilesDir("wallet-backups") ?: filesDir
                            val outOld = File(dir, "wallet-${System.currentTimeMillis()}.webd")
                            val legacyJson = LegacyWalletFormat.exportFromSecretHex(secret)
                            outOld.writeText(legacyJson)
                            outOld
                        }.onSuccess { file ->
                            Toast.makeText(this, "Export .webd salvat: ${file.absolutePath}", Toast.LENGTH_LONG).show()
                        }.onFailure {
                            Toast.makeText(this, "Export eșuat: ${it.message}", Toast.LENGTH_LONG).show()
                        }
                        return@setItems
                    }

                    showPassphraseDialog("Parolă export backup") { passphrase ->
                        runCatching {
                            val dir = getExternalFilesDir("wallet-backups") ?: filesDir
                            val outOld = File(dir, "wallet-${System.currentTimeMillis()}.webd")
                            val outEnc = File(dir, "wallet-${System.currentTimeMillis()}.webd.enc")

                            // 1) Export format vechi (legacy) necriptat
                            val legacyJson = LegacyWalletFormat.exportFromSecretHex(secret)
                            outOld.writeText(legacyJson)

                            // 2) Export criptat (backup)
                            val payload = WalletBackupPayload(
                                address = address,
                                secretHex = secret,
                                createdAt = System.currentTimeMillis()
                            )
                            WalletBackupCrypto.exportEncrypted(payload, passphrase, outEnc)

                            // 3) Opțional: activează password mode pentru unlock la mining
                            MinerPrefs.walletSecretEncrypted = LocalWalletCipher.encryptSecret(secret, passphrase)
                            MinerPrefs.walletPasswordMode = true

                            Pair(outOld, outEnc)
                        }.onSuccess { file ->
                            Toast.makeText(
                                this,
                                "Export .webd + .enc salvate: ${file.first.absolutePath}",
                                Toast.LENGTH_LONG
                            ).show()
                        }.onFailure {
                            Toast.makeText(this, "Export eșuat: ${it.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
                .show()
            refreshWalletBackupPreview()
        }

        binding.btnImportSecret.setOnClickListener { showLegacyImportOptionsDialog() }

        binding.btnStart.setOnClickListener {
            if (binding.etWallet.text.isNullOrBlank()) {
                Toast.makeText(this, "Wallet address este obligatoriu", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (MinerPrefs.walletPasswordMode && MinerPrefs.walletSecret.isBlank()) {
                showPassphraseDialog("Deblochează wallet pentru mining") { pass ->
                    runCatching {
                        val secret = LocalWalletCipher.decryptSecret(MinerPrefs.walletSecretEncrypted, pass)
                        val w = WalletGenerator.fromSecretHex(secret)
                        MinerPrefs.walletSecret = w.secretHex
                        MinerPrefs.walletAddress = w.address
                        binding.etWallet.setText(w.address)
                    }.onSuccess {
                        startMiningNow()
                    }.onFailure {
                        Toast.makeText(this, "Parolă invalidă sau wallet corupt", Toast.LENGTH_LONG).show()
                    }
                }
            } else {
                startMiningNow()
            }
        }

        binding.btnStop.setOnClickListener {
            startService(MinerService.stopIntent(this))
        }
    }

    private fun startMiningNow() {
        vm.setPoolUrl(binding.etPoolUrl.text.toString())
        vm.setPoolKey(binding.etPoolKey.text.toString())
        vm.setWalletAddress(binding.etWallet.text.toString())
        vm.setOnlyWhenCharging(binding.swOnlyCharging.isChecked)
        vm.saveConfig()

        // Feedback imediat pentru cel mai frecvent motiv de "nu pornește".
        if (MinerPrefs.onlyWhenCharging && !isDeviceCharging()) {
            Toast.makeText(this, "Mining este setat doar la priză. Conectează telefonul sau dezactivează opțiunea.", Toast.LENGTH_LONG).show()
            return
        }

        runCatching {
            ContextCompat.startForegroundService(this, MinerService.startIntent(this))
        }.onFailure {
            Toast.makeText(this, "Start eșuat: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun isDeviceCharging(): Boolean {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun observeStats() {
        val s = service ?: return
        statsJob?.cancel()
        statsJob = lifecycleScope.launch {
            s.statsFlow().collectLatest { stats ->
                renderStats(stats)
            }
        }
    }

    private fun renderStats(stats: MinerStats) {
        binding.tvStatus.text = if (stats.running) "RUNNING" else "STOPPED"
        binding.tvHashrate.text = "${stats.hashrate} H/s"
        binding.tvShares.text = "Accepted ${stats.sharesAccepted} | Rejected ${stats.sharesRejected} | Stale ${stats.sharesStale}"
        binding.tvReward.text = "Pending reward: ${stats.rewardPending}"
        binding.tvHeight.text = "Height: ${stats.currentHeight}"
        binding.tvLast.text = "Last: ${stats.lastResult}"
        binding.tvError.text = stats.error
    }

    private fun refreshWalletBackupPreview() {
        val secret = MinerPrefs.walletSecret
        binding.tvWalletBackup.text = if (secret.isBlank()) {
            if (MinerPrefs.walletPasswordMode && MinerPrefs.walletSecretEncrypted.isNotBlank()) {
                "Backup secret: criptat local (password mode ON)"
            } else {
                "Backup secret: (none)"
            }
        } else {
            val head = secret.take(10)
            val tail = secret.takeLast(6)
            "Backup secret: ${head}...${tail}"
        }
    }

    private fun copyToClipboard(label: String, value: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, value))
    }

    private fun showPassphraseDialog(title: String, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            hint = "Minim 8 caractere"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val pass = input.text?.toString().orEmpty()
                if (pass.length < 8) {
                    Toast.makeText(this, "Parola prea scurtă", Toast.LENGTH_SHORT).show()
                } else {
                    onOk(pass)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showLegacyImportDialog() {
        val input = EditText(this).apply {
            hint = "Lipește format vechi JSON / privateKeyWIF / secret hex"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle("Import wallet (format vechi)")
            .setView(input)
            .setPositiveButton("Import") { _, _ ->
                val raw = input.text?.toString().orEmpty()
                importLegacyRaw(raw)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showLegacyImportOptionsDialog() {
        AlertDialog.Builder(this)
            .setTitle("Import necriptat")
            .setItems(arrayOf("Din text", "Din fișier .webd")) { _, which ->
                when (which) {
                    0 -> showLegacyImportDialog()
                    1 -> importWebdLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                }
            }
            .show()
    }

    private fun importLegacyRaw(raw: String) {
        runCatching {
            LegacyWalletFormat.importToWallet(raw)
        }.onSuccess { wallet ->
            MinerPrefs.walletSecret = wallet.secretHex
            MinerPrefs.walletAddress = wallet.address
            MinerPrefs.walletPasswordMode = false
            MinerPrefs.walletSecretEncrypted = ""
            vm.setWalletAddress(wallet.address)
            binding.etWallet.setText(wallet.address)
            refreshWalletBackupPreview()
            Toast.makeText(this, "Wallet legacy importat", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(this, "Import eșuat: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }
}
