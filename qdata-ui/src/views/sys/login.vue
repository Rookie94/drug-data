<template>
  <div class="login-wrapper" ref="app-container">
    <!-- Left Hero & Branding Section -->
    <div class="login-left">
      <!-- Ambient light effect -->
      <div class="ambient-glow glow-top"></div>
      <div class="ambient-glow glow-bottom"></div>
      <div class="tech-grid-overlay"></div>

      <div class="left-hero-content">
        <div class="platform-badge">
          <span class="badge-dot"></span>
          <span>ENTERPRISE DATA PLATFORM</span>
        </div>

        <h1 class="hero-title">“一体两翼”信息化数据平台</h1>
        <p class="hero-subtitle">全域数据资产纳管 · 敏捷数据研发 · 统一数据服务治理</p>

        <div class="system-status-pill">
          <span class="status-dot"></span>
          <span>系统状态：服务正常运行</span>
        </div>
      </div>
    </div>

    <!-- Right Login Form Section -->
    <div class="login-right">
      <div class="login-card-container">
        <div class="login-card">
          <el-form ref="loginRef" :model="loginForm" :rules="loginRules" class="auth-form">
            <el-form-item prop="username">
              <el-input
                v-model="loginForm.username"
                type="text"
                size="large"
                auto-complete="off"
                :placeholder="td('login.username')"
              >
                <template #prefix>
                  <i class="iconfont">&#xebc0;</i>
                </template>
              </el-input>
            </el-form-item>

            <el-form-item prop="password">
              <el-input
                v-model="loginForm.password"
                type="password"
                size="large"
                auto-complete="off"
                show-password
                :placeholder="td('login.password')"
                @keyup.enter="handleLogin"
              >
                <template #prefix>
                  <i class="iconfont">&#xeb8d;</i>
                </template>
              </el-input>
            </el-form-item>

            <el-form-item prop="code" v-if="captchaEnabled">
              <div class="captcha-row">
                <el-input
                  v-model="loginForm.code"
                  size="large"
                  auto-complete="off"
                  :placeholder="td('login.code')"
                  @keyup.enter="handleLogin"
                >
                  <template #prefix>
                    <i class="iconfont">&#xeb9e;</i>
                  </template>
                </el-input>
                <div class="captcha-img-wrapper" @click="getCode" title="点击刷新验证码">
                  <img :src="codeUrl" alt="验证码" class="captcha-image" />
                </div>
              </div>
            </el-form-item>

            <div class="form-extra-row">
              <el-checkbox v-model="loginForm.rememberMe">{{ td('login.remember') }}</el-checkbox>
              <el-button link type="primary" class="forget-link" @click="dialogVisible = true">
                {{ td('login.forgetPassword') }}
              </el-button>
            </div>

            <el-form-item class="submit-form-item">
              <el-button
                :loading="loading"
                type="primary"
                size="large"
                class="login-button"
                @click="handleLogin"
              >
                <span v-if="!loading">{{ td('login.loginBtn') }}</span>
                <span v-else>{{ td('login.logining') }}</span>
              </el-button>
            </el-form-item>
          </el-form>
        </div>
      </div>
    </div>

    <!-- Forget Password Modal -->
    <el-dialog
      v-model="dialogVisible"
      :title="td('login.forgetPassword')"
      class="fp-form-dialog"
      width="540px"
      :append-to="$refs['app-container']"
      draggable
      destroy-on-close
    >
      <el-form :model="fpForm" label-width="80px" style="padding: 10px 20px 0">
        <el-form-item :label="td('login.username')">
          <el-input v-model="fpForm.username" :placeholder="td('login.usernameInputPlaceholder')" />
        </el-form-item>
        <el-form-item :label="td('login.code')">
          <div class="fp-code-row">
            <el-input v-model="fpForm.code" :placeholder="td('login.codePlaceholder')" />
            <el-button type="primary" :disabled="codeFlag" @click="handleFPCodeClick">
              {{ codeFlag ? `${codeTime}s` : td('login.getCode') }}
            </el-button>
          </div>
        </el-form-item>
        <el-form-item :label="td('login.newPassword')">
          <el-input v-model="fpForm.password" show-password :placeholder="td('login.newPasswordPlaceholder')" />
        </el-form-item>
        <el-form-item :label="td('login.confirmPassword')">
          <el-input v-model="fpForm.password2" show-password :placeholder="td('login.confirmPasswordPlaceholder')" />
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button @click="dialogVisible = false">{{ td('common.button.cancel') || '取消' }}</el-button>
          <el-button type="primary" @click="dialogVisible = false">{{ td('login.resetPassword') }}</el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, getCurrentInstance } from 'vue';
import useDefaultLang from '@/composables/useDefaultLang';
import { getCodeImg } from '@/api/system/login';
import Cookies from 'js-cookie';
import { encrypt, decrypt } from '@/utils/jsencrypt';
import useUserStore from '@/store/system/user.js';
import { getContent } from '@/api/system/system/content';
import defaultLogo from '@/assets/images/system/login/logo-qdata.png';

const { td } = useDefaultLang();
const userStore = useUserStore();
const { proxy } = getCurrentInstance();

const dialogVisible = ref(false);
const loading = ref(false);
const codeUrl = ref('');
const captchaEnabled = ref(true);
const codeFlag = ref(false);

const loginForm = ref({
  username: 'qData',
  password: 'qData123',
  rememberMe: false,
  code: '',
  uuid: ''
});

const fpForm = ref({
  username: '',
  password: '',
  password2: '',
  code: ''
});

const loginRules = computed(() => ({
  username: [{ required: true, trigger: 'blur', message: td('login.usernameRequired') }],
  password: [{ required: true, trigger: 'blur', message: td('login.passwordRequired') }],
  code: [{ required: true, trigger: 'change', message: td('login.codeRequired') }]
}));

const logo = ref(defaultLogo);
const contentDetail = ref(null);

const preloadImage = (src) =>
  new Promise((resolve, reject) => {
    const image = new Image();
    image.onload = () => resolve(src);
    image.onerror = reject;
    image.src = src;
  });

onMounted(() => {
  fetchContent();
});

const fetchContent = async () => {
  try {
    const res = await getContent(1);
    if (res.code == 200) {
      const data = res.data;
      contentDetail.value = data;
      const sysLogo = data.loginLogo;
      if (sysLogo) {
        preloadImage(sysLogo)
          .then(() => {
            logo.value = sysLogo;
          })
          .catch(() => {});
      }
    }
  } catch {
    // Non-blocking fallback
  }
};

function getCookie() {
  const username = Cookies.get('username');
  const password = Cookies.get('password');
  const rememberMe = Cookies.get('rememberMe');
  loginForm.value = {
    username: username === undefined ? loginForm.value.username : username,
    password: password === undefined ? loginForm.value.password : decrypt(password),
    rememberMe: rememberMe === undefined ? false : Boolean(rememberMe)
  };
}
getCookie();

function getCode() {
  getCodeImg().then((res) => {
    captchaEnabled.value = res.captchaEnabled === undefined ? true : res.captchaEnabled;
    if (captchaEnabled.value) {
      codeUrl.value = 'data:image/gif;base64,' + res.img;
      loginForm.value.uuid = res.uuid;
    }
  });
}
getCode();

function handleLogin() {
  localStorage.setItem('username', loginForm.value.username);
  proxy.$refs.loginRef.validate((valid) => {
    if (valid) {
      loading.value = true;
      if (loginForm.value.rememberMe) {
        Cookies.set('username', loginForm.value.username, { expires: 30 });
        Cookies.set('password', encrypt(loginForm.value.password), { expires: 30 });
        Cookies.set('rememberMe', loginForm.value.rememberMe, { expires: 30 });
      } else {
        Cookies.remove('username');
        Cookies.remove('password');
        Cookies.remove('rememberMe');
      }

      userStore
        .login(loginForm.value)
        .then(() => {
          window.location.href = '/index';
        })
        .catch(() => {
          loading.value = false;
          if (captchaEnabled.value) {
            getCode();
          }
        });
    }
  });
}

const timeValue = 60;
const codeTime = ref(timeValue);
function handleFPCodeClick() {
  if (codeFlag.value) return;
  codeFlag.value = true;
  const timer = setInterval(() => {
    if (codeTime.value > 1) {
      codeTime.value--;
    } else {
      clearInterval(timer);
      codeTime.value = timeValue;
      codeFlag.value = false;
    }
  }, 1000);
}
</script>

<style scoped lang="scss">
.login-wrapper {
  display: flex;
  width: 100vw;
  height: 100vh;
  overflow: hidden;
  background-color: #f8fafc;
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
}

/* ================= Left Hero Section ================= */
.login-left {
  flex: 0 0 52%;
  position: relative;
  background: linear-gradient(135deg, #070e1e 0%, #0c1a36 40%, #102652 100%);
  overflow: hidden;
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  padding: 60px 80px;
  color: #ffffff;
  user-select: none;
}

.ambient-glow {
  position: absolute;
  border-radius: 50%;
  filter: blur(100px);
  pointer-events: none;
  opacity: 0.45;
}

.glow-top {
  width: 500px;
  height: 500px;
  background: radial-gradient(circle, #2563eb 0%, transparent 70%);
  top: -150px;
  left: -100px;
}

.glow-bottom {
  width: 600px;
  height: 600px;
  background: radial-gradient(circle, #0284c7 0%, transparent 70%);
  bottom: -200px;
  right: -150px;
}

.tech-grid-overlay {
  position: absolute;
  inset: 0;
  background-size: 40px 40px;
  background-image: 
    linear-gradient(to right, rgba(255, 255, 255, 0.03) 1px, transparent 1px),
    linear-gradient(to bottom, rgba(255, 255, 255, 0.03) 1px, transparent 1px);
  mask-image: radial-gradient(ellipse at center, black 40%, transparent 80%);
  pointer-events: none;
}

.left-hero-content {
  position: relative;
  z-index: 2;
  max-width: 620px;
  width: 100%;
}

.platform-badge {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 6px 14px;
  background: rgba(37, 99, 235, 0.18);
  border: 1px solid rgba(59, 130, 246, 0.35);
  border-radius: 20px;
  font-size: 12px;
  letter-spacing: 1.5px;
  color: #60a5fa;
  font-weight: 600;
  margin-bottom: 24px;
}

.badge-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background-color: #3b82f6;
  box-shadow: 0 0 8px #60a5fa;
}

.hero-title {
  font-size: 42px;
  font-weight: 700;
  line-height: 1.25;
  letter-spacing: -0.5px;
  margin: 0 0 14px 0;
  background: linear-gradient(180deg, #ffffff 0%, #cbd5e1 100%);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
}

.hero-subtitle {
  font-size: 16px;
  line-height: 1.6;
  color: #94a3b8;
  margin: 0 0 40px 0;
}

.system-status-pill {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: #94a3b8;
}

.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background-color: #10b981;
  box-shadow: 0 0 10px #34d399;
}

/* ================= Right Login Section ================= */
.login-right {
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  padding: 36px 60px;
  background-color: #ffffff;
  position: relative;
}

.login-card-container {
  display: flex;
  justify-content: center;
  align-items: center;
  flex: 1;
  padding: 20px 0;
}

.login-card {
  width: 100%;
  max-width: 410px;
}

.auth-form {
  ::v-deep(.el-form-item) {
    margin-bottom: 22px;
  }

  ::v-deep(.el-input__wrapper) {
    padding: 2px 14px;
    border-radius: 8px;
    box-shadow: 0 0 0 1px #e2e8f0 inset;
    transition: all 0.2s;

    &:hover {
      box-shadow: 0 0 0 1px #cbd5e1 inset;
    }

    &.is-focus {
      box-shadow: 0 0 0 1px #2563eb inset, 0 0 0 3px rgba(37, 99, 235, 0.12) !important;
    }
  }

  .iconfont {
    font-size: 16px;
    color: #94a3b8;
    margin-right: 6px;
  }
}

.captcha-row {
  display: flex;
  gap: 12px;
  width: 100%;
}

.captcha-img-wrapper {
  flex-shrink: 0;
  width: 120px;
  height: 40px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  overflow: hidden;
  cursor: pointer;
  background: #f8fafc;
  display: flex;
  align-items: center;
  justify-content: center;

  .captcha-image {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
}

.form-extra-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 24px;

  .forget-link {
    font-size: 13px;
    padding: 0;
    color: #2563eb;

    &:hover {
      color: #1d4ed8;
    }
  }
}

.submit-form-item {
  margin-bottom: 0 !important;
}

.login-button {
  width: 100%;
  height: 44px;
  font-size: 16px;
  font-weight: 600;
  border-radius: 8px;
  background: linear-gradient(135deg, #2563eb 0%, #1d4ed8 100%);
  border: none;
  box-shadow: 0 4px 12px rgba(37, 99, 235, 0.28);
  transition: all 0.2s ease;

  &:hover {
    box-shadow: 0 6px 16px rgba(37, 99, 235, 0.38);
    transform: translateY(-1px);
  }

  &:active {
    transform: translateY(0);
  }
}

/* ================= Dialog Style ================= */
.fp-code-row {
  display: flex;
  gap: 10px;
  width: 100%;

  .el-button {
    width: 110px;
  }
}

/* ================= Responsive Breakpoints ================= */
@media (max-width: 1080px) {
  .login-left {
    display: none;
  }

  .login-right {
    flex: 1;
    padding: 30px;
  }
}
</style>
