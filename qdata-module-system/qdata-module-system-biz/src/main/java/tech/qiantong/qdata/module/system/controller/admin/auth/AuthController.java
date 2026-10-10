/*
 * Copyright © 2025-present Jiangsu Qiantong Technology Co., Ltd.
 *
 * This file is part of qData Data Middle Platform (Open Source Edition).
 *
 * qData is licensed under Apache License 2.0 with additional qData terms.
 * You may use qData for commercial purposes, but you may not remove, hide,
 * modify, or replace the qData logo, copyright notices, license notices,
 * or attribution information without a separate commercial license.
 *
 * White-label use, OEM distribution, rebranding, or presenting qData as
 * another product requires separate commercial authorization from
 * Jiangsu Qiantong Technology Co., Ltd.
 *
 * Business License: https://community.qdata.tech/business/policy.html
 * See the LICENSE file in the project root for full license information.
 */

package tech.qiantong.qdata.module.system.controller.admin.auth;

import cn.dev33.satoken.oauth2.template.SaOAuth2Util;
import cn.hutool.core.convert.Convert;
import com.ejlchina.okhttps.OkHttps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tech.qiantong.qdata.common.core.domain.AjaxResult;
import tech.qiantong.qdata.common.core.domain.entity.SysUser;
import tech.qiantong.qdata.common.core.domain.model.LoginUser;
import tech.qiantong.qdata.common.exception.ServiceException;
import tech.qiantong.qdata.common.utils.SoMap;
import tech.qiantong.qdata.common.utils.StringUtils;
import tech.qiantong.qdata.module.system.dal.dataobject.auth.RelUserAuthProductDO;
import tech.qiantong.qdata.module.system.enums.auth.AuthProductEnums;
import tech.qiantong.qdata.module.system.service.ISysUserService;
import tech.qiantong.qdata.module.system.service.auth.IRelUserAuthProductService;
import java.util.Map;
import tech.qiantong.qdata.common.core.redis.RedisCache;
import tech.qiantong.qdata.common.utils.SecurityUtils;
import tech.qiantong.qdata.common.utils.uuid.IdUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import tech.qiantong.qdata.module.system.dal.mapper.auth.RelUserAuthProductMapper;
import tech.qiantong.qdata.redis.service.IRedisService;
import tech.qiantong.qdata.security.web.service.SysPermissionService;
import tech.qiantong.qdata.security.web.service.TokenService;

/**
 * oauth2 Controller
 *
 * @author surge
 * @date 2022-09-16
 */
@RestController
@RequestMapping("/oauth2")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    @Autowired
    private ISysUserService userService;
    @Autowired
    private SysPermissionService permissionService;
    @Autowired
    private TokenService tokenService;
    @Autowired
    private IRedisService redisService;
    @Autowired
    private IRelUserAuthProductService userAuthProductService;
    @Autowired
    private RelUserAuthProductMapper relUserAuthProductMapper;
    @Autowired
    private RedisCache redisCache;

    @Value("${oauth2.redis-prefix}")
    private String redisPrefix;

    // redis secondary folder naming
    public static final String accessTokenPrefix = "accessToken";
    public static final String refreshTokenPrefix = "refreshToken";

    // related parameter configuration
    @Value("${oauth2.clientId}")
    private String clientId;            // application ID
    @Value("${oauth2.clientSecret}")
    private String clientSecret;        // application secret
    @Value("${oauth2.serverUrl}")
    private String serverUrl;           // server endpoint

    // login via authorization code to obtain Access-Token and openid
    @RequestMapping("/codeLogin")
    @Transactional
    public AjaxResult codeLogin(String code) {
        // call server endpoint to obtain Access-Token and related info
        String str = OkHttps.sync(serverUrl + "/oauth2/token")
                .addBodyPara("grant_type", "authorization_code")
                .addBodyPara("code", code)
                .addBodyPara("client_id", clientId)
                .addBodyPara("client_secret", clientSecret)
                .post()
                .getBody()
                .toString();
        SoMap so = SoMap.getSoMap().setJsonString(str);
        System.out.println("response: " + so);

        // code != 200 means request failed
        if(so.getInt("code") != 200) {
            return AjaxResult.error(so.getString("msg"));
        }

        // get userId by openid
        SoMap data = so.getMap("data");

        // idHubId
        Long idHubId = data.getLong("idHubId");
        // Access-Token value
        String accessToken = data.getString("access_token");
        // Refresh-Token value
        String refreshToken = data.getString("refresh_token");
        // Access-Token remaining validity in seconds
        long expiresIn = data.getLong("expires_in");
        // Refresh-Token remaining validity in seconds
        long refreshExpiresIn = data.getLong("refresh_expires_in");

        SysUser user = this.getUserByIdHubId(idHubId);

        if (user == null) {
            // get userInfo via openid
            SoMap userinfo = this.getUserinfo(accessToken);
            if (userinfo != null) {
                // unified identity authentication phone number
                String phone = userinfo.getString("phone");
                // find user by phone number
                SysUser userByPhone = userService.findUserByNameOrPhone(phone);

                if (userByPhone != null) {
                    RelUserAuthProductDO productDO = RelUserAuthProductDO.builder()
                            .userId(userByPhone.getUserId())
                            .authId(idHubId.toString())
                            .authProductType(AuthProductEnums.ANIVIA.code)
                            .build();

                    userAuthProductService.save(productDO);
                    user = userByPhone;
                } else {
                    return AjaxResult.error("system.user.notfound");
                }
            } else {
                return AjaxResult.error("system.error.auth.fetchUserInfo");
            }
        }

        // store in redis
        redisService.set(redisPrefix + ":" + accessTokenPrefix + ":" + user.getUserId().toString(), accessToken, expiresIn);
        redisService.set(redisPrefix + ":" + refreshTokenPrefix + ":" + user.getUserId().toString(), refreshToken, refreshExpiresIn);

        // create login user (password-free)
        LoginUser loginUser = createLoginUser(user);

        // obtain password-free token
        String token = tokenService.createToken(loginUser);

        log.info("User: {} logged in via unified identity authentication platform successfully!", user.getUserName());
        return AjaxResult.success(token);
    }


    // refresh Access-Token
    public String refresh(String refreshToken) {
            // call server endpoint to refresh a new Access-Token via Refresh-Token
            String str = OkHttps.sync(serverUrl + "/oauth2/refresh")
                .addBodyPara("grant_type", "refresh_token")
                .addBodyPara("client_id", clientId)
                .addBodyPara("client_secret", clientSecret)
                .addBodyPara("refresh_token", refreshToken)
                .post()
                .getBody()
                .toString();
        SoMap so = SoMap.getSoMap().setJsonString(str);
        System.out.println("response: " + so);

        // code != 200 means request failed
        if(so.getInt("code") != 200) {
            return null;
        }

        // return related parameters (data=new Access-Token)
        SoMap data = so.getMap("data");

        // openid
        String openid = data.getString("openid");
        // Access-Token value
        String accessToken = data.getString("access_token");
        // Refresh-Token value
        String refToken = data.getString("refresh_token");
        // Access-Token remaining validity in seconds
        long expiresIn = data.getLong("expires_in");
        // Refresh-Token remaining validity in seconds
        long refreshExpiresIn = data.getLong("refresh_expires_in");

        Long userId = Convert.toLong(SaOAuth2Util.getLoginIdByAccessToken(accessToken));

        // store in redis
        redisService.set(redisPrefix + ":" + accessTokenPrefix + ":" + userId.toString(), accessToken, expiresIn);
        redisService.set(redisPrefix + ":" + refreshTokenPrefix + ":" + userId.toString(), refreshToken, refreshExpiresIn);

        return data.getString("access_token");
    }

    public LoginUser createLoginUser(SysUser user)
    {
        return new LoginUser(user.getUserId(), user.getDeptId(), user, permissionService.getMenuPermission(user));
    }

    /**
     * Get user by idHubId
     * @param idHubId unified identity authentication ID
     * @return user
     */
    private SysUser getUserByIdHubId(Long idHubId) {
        // Auth platform association
        LambdaQueryWrapper<RelUserAuthProductDO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RelUserAuthProductDO::getUserId, idHubId)
                .eq(RelUserAuthProductDO::getAuthProductType, AuthProductEnums.ANIVIA.code);
        RelUserAuthProductDO authInfo = relUserAuthProductMapper.selectOne(wrapper);

        if (authInfo != null) {
            return userService.selectUserById(authInfo.getUserId());
        }
        return null;
    }

    /**
     * Exchange Access-Token for resources: get account nickname, avatar, gender, etc.
     * @param accessToken
     * @return
     */
    @RequestMapping("/getUserinfo")
    public SoMap getUserinfo(String accessToken) {
        // call server endpoint to query open resources
        String str = OkHttps.sync(serverUrl + "/oauth2/userinfo")
                .addBodyPara("access_token", accessToken)
                .post()
                .getBody()
                .toString();
        SoMap so = SoMap.getSoMap().setJsonString(str);
        System.out.println("response: " + so);

        // code != 200 means request failed
        if(so.getInt("code") != 200) {
            throw new ServiceException("system.error.account.fetch");
        }

        // return related parameters (data=obtained resources)
        return so.getMap("data");
    }

    /**
     * Logout
     * @param userId
     * @return
     */
    @RequestMapping("/sso/logout")
    public AjaxResult loginOut(String userId) {
        String accessToken = redisService.get(redisPrefix + ":" + accessTokenPrefix + ":" + userId);

        if (StringUtils.isEmpty(accessToken)) {
            String refreshToken = redisService.get(redisPrefix + ":" + refreshTokenPrefix + ":" + userId);
            accessToken = this.refresh(refreshToken);
        }

        // call server endpoint to query open resources
        String str = OkHttps.sync(serverUrl + "/oauth2/logout")
                .addBodyPara("access_token", accessToken)
                .post()
                .getBody()
                .toString();
        SoMap so = SoMap.getSoMap().setJsonString(str);
        System.out.println("response: " + so);

        return AjaxResult.success(so);
    }

    /**
     * 一体两翼 SSO 单点登录：支持无感自动建号（JIT Provisioning）
     */
    @RequestMapping(value = {"/sso/codeLogin", "/codeLoginYiti"})
    @Transactional(rollbackFor = Exception.class)
    public AjaxResult ssoCodeLogin(String code) {
        if (StringUtils.isEmpty(code)) {
            return AjaxResult.error("授权码不能为空");
        }

        String codeKey = "sso_code:" + code;
        Map<String, Object> codeData = redisCache.getCacheObject(codeKey);
        if (codeData == null) {
            return AjaxResult.error("授权码无效或已过期，请重新发起跳转");
        }
        // 立即删除，防止重放攻击
        redisCache.deleteObject(codeKey);

        String username = Convert.toStr(codeData.get("username"));
        String nickName = Convert.toStr(codeData.get("nickName"));
        String phonenumber = Convert.toStr(codeData.get("phonenumber"));
        String email = Convert.toStr(codeData.get("email"));
        String sex = Convert.toStr(codeData.get("sex"));
        String sourceUserId = Convert.toStr(codeData.get("sourceUserId"));

        if (StringUtils.isEmpty(username)) {
            return AjaxResult.error("授权码中未包含有效用户信息");
        }

        // 1. 查找用户（按优先级：映射表 -> 用户名 -> 手机号）
        SysUser user = findUserBySsoStrategy(sourceUserId, username, phonenumber);

        // 2. 如果不存在，自动即时建号（JIT 开户）
        if (user == null) {
            user = new SysUser();
            user.setUserName(username);
            user.setNickName(StringUtils.isNotEmpty(nickName) ? nickName : username);
            user.setPhonenumber(phonenumber);
            user.setEmail(email);
            user.setSex(StringUtils.isNotEmpty(sex) ? sex : "2");
            // 生成强随机密码（SSO 用户走免密登录，防空密码漏洞）
            user.setPassword(SecurityUtils.encryptPassword(IdUtils.fastUUID()));
            user.setStatus("0");
            user.setDelFlag("0");

            // 分配默认角色：role_id=7 (体验用户)
            user.setRoleIds(new Long[]{ 7L });

            // 插入用户及角色关联
            userService.insertUser(user);
            log.info("SSO 自动开通 qData 账号成功: userId={}, userName={}", user.getUserId(), username);
        }

        // 3. 检查并建立/补齐映射关系
        bindAuthMapping(user.getUserId(), sourceUserId);

        // 4. 账号状态校验
        if ("2".equals(user.getDelFlag())) {
            return AjaxResult.error("该账号已被删除，无法登录");
        }
        if ("1".equals(user.getStatus())) {
            return AjaxResult.error("该账号已被停用，请联系管理员");
        }

        // 5. 生成 qData 本地 JWT Token
        LoginUser loginUser = createLoginUser(user);
        String token = tokenService.createToken(loginUser);

        log.info("用户 [{}] 通过一体两翼 SSO 登录成功", user.getUserName());
        return AjaxResult.success("登录成功", token);
    }

    /**
     * 用户查找优先级策略
     */
    private SysUser findUserBySsoStrategy(String sourceUserId, String username, String phone) {
        // 1. 优先查已有映射关系
        if (StringUtils.isNotEmpty(sourceUserId)) {
            LambdaQueryWrapper<RelUserAuthProductDO> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(RelUserAuthProductDO::getAuthId, sourceUserId)
                    .eq(RelUserAuthProductDO::getAuthProductType, AuthProductEnums.YITILIANGYI.code);
            RelUserAuthProductDO authInfo = relUserAuthProductMapper.selectOne(wrapper);
            if (authInfo != null && authInfo.getUserId() != null) {
                SysUser mappedUser = userService.selectUserById(authInfo.getUserId());
                if (mappedUser != null) {
                    return mappedUser;
                }
            }
        }

        // 2. 尝试按用户名匹配
        if (StringUtils.isNotEmpty(username)) {
            SysUser byName = userService.selectUserByUserName(username);
            if (byName != null) {
                return byName;
            }
        }

        // 3. 若有手机号，尝试按手机号匹配（解决两边用户名不一致但手机号相同的情况）
        if (StringUtils.isNotEmpty(phone)) {
            SysUser byPhone = userService.findUserByNameOrPhone(phone);
            if (byPhone != null) {
                return byPhone;
            }
        }

        return null;
    }

    /**
     * 关联或更新映射表记录
     */
    private void bindAuthMapping(Long qDataUserId, String sourceUserId) {
        if (StringUtils.isEmpty(sourceUserId) || qDataUserId == null) {
            return;
        }
        LambdaQueryWrapper<RelUserAuthProductDO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RelUserAuthProductDO::getAuthId, sourceUserId)
                .eq(RelUserAuthProductDO::getAuthProductType, AuthProductEnums.YITILIANGYI.code);
        Long count = relUserAuthProductMapper.selectCount(wrapper);
        if (count == null || count == 0) {
            RelUserAuthProductDO record = RelUserAuthProductDO.builder()
                    .userId(qDataUserId)
                    .authId(sourceUserId)
                    .authProductType(AuthProductEnums.YITILIANGYI.code)
                    .build();
            relUserAuthProductMapper.insert(record);
        }
    }
}
