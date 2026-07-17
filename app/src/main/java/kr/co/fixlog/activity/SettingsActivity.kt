package kr.co.fixlog.activity

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kr.co.fixlog.data.remote.AuthApi
import kr.co.fixlog.databinding.ActivitySettingsBinding
import kr.co.fixlog.util.TokenManager
import org.json.JSONObject

/**
 * 설정 화면. 하단 "전체" 메뉴의 설정 셀에서 진입한다.
 * - 현재 로그인 계정 정보(이름/이메일)를 서버 세션(GET /auth/token)에서 조회해 표시.
 * - 로그아웃: 서버에 로그아웃 API가 없어 클라이언트에서 토큰을 삭제하고 로그인 화면으로 되돌린다.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        binding.btnBack.setOnClickListener { finish() }
        binding.btnLogout.setOnClickListener { confirmLogout() }

        loadAccount()
    }

    /**
     * 서버 세션(GET /auth/token)에서 계정 정보를 가져와 표시한다.
     * 실패(비로그인/네트워크)해도 로그아웃은 가능하도록 화면은 유지하고 안내 문구만 바꾼다.
     */
    private fun loadAccount() {
        lifecycleScope.launch {
            runCatching { AuthApi.session() }
                .onSuccess { raw -> renderAccount(raw) }
                .onFailure { e ->
                    Log.d(TAG, "세션 조회 실패: ${e.message}")
                    binding.tvUserName.text = "로그인 정보를 불러오지 못했습니다"
                    binding.tvUserEmail.text = ""
                }
        }
    }

    private fun renderAccount(rawResult: String) {
        val obj = runCatching { JSONObject(rawResult) }.getOrNull()
        val name = obj?.optString("userName").orEmptyIfBlank()
        val email = obj?.optString("email").orEmptyIfBlank()

        binding.tvUserName.text = name ?: "이름 없음"
        binding.tvUserEmail.text = email ?: ""
        binding.tvAvatar.text = avatarInitial(name ?: email ?: "")
    }

    /** 이름/이메일에서 아바타 이니셜(최대 2글자) 생성. */
    private fun avatarInitial(source: String): String {
        val trimmed = source.trim()
        if (trimmed.isEmpty()) return "·"
        val parts = trimmed.split(Regex("\\s+"))
        val initials = if (parts.size >= 2) {
            "${parts[0].first()}${parts[1].first()}"
        } else {
            trimmed.take(2)
        }
        return initials.uppercase()
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle("로그아웃")
            .setMessage("로그아웃 하시겠습니까?")
            .setNegativeButton("취소", null)
            .setPositiveButton("로그아웃") { _, _ -> logout() }
            .show()
    }

    /**
     * 클라이언트 토큰을 삭제하고 로그인 화면으로 되돌린다.
     * 백 스택 전체를 비워(NEW_TASK|CLEAR_TASK) 뒤로가기로 이전 화면에 복귀하지 못하게 한다.
     */
    private fun logout() {
        TokenManager.clear(this)
        val intent = Intent(this, GoogleLoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    private fun String?.orEmptyIfBlank(): String? = this?.takeIf { it.isNotBlank() }

    companion object {
        private const val TAG = "SettingsActivity"
    }
}
