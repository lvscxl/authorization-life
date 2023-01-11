package com.authorization.life.auth.infra.security.handler.sso;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.authorization.core.entity.UserDetail;
import com.authorization.redis.start.service.StringRedisService;
import com.authorization.utils.json.JsonHelper;
import com.authorization.utils.jwt.Jwts;
import com.authorization.utils.result.R;
import com.authorization.utils.security.SecurityConstant;
import com.authorization.utils.security.SsoSecurityProperties;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.JWSVerifier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.web.authentication.logout.LogoutHandler;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 退出登录处理类
 */
@Slf4j
public class SsoLogoutHandle implements LogoutHandler {

    private final OAuth2AuthorizationService oAuth2AuthorizationService;
    private final StringRedisService stringRedisService;
    private final JWSVerifier verifier;

    public SsoLogoutHandle(OAuth2AuthorizationService oAuth2AuthorizationService, StringRedisService stringRedisService, SsoSecurityProperties ssoSecurityProperties) {
        this.oAuth2AuthorizationService = oAuth2AuthorizationService;
        this.stringRedisService = stringRedisService;
        this.verifier = Jwts.verifier(ssoSecurityProperties.getSecret());
    }

    @Override
    public void logout(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        log.info("进入退出登录处理器。");
        response.setStatus(HttpStatus.OK.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.toString());
        // 前端传参的 accesstoken
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        // 网关层次解析后的 jwtToken
        String interiorJwt = request.getHeader(Jwts.HEADER_JWT);
        log.debug("请求头-Authorization-是：" + authorization);
        if (StrUtil.isBlank(authorization) || StrUtil.isBlank(interiorJwt)) {
            try {
                PrintWriter out = response.getWriter();
                out.write(JSONUtil.toJsonStr(new R<>(R.ERROR, "未找到请求头的token，请确认已登录。", null)));
                out.flush();
                out.close();
            } catch (IOException e) {
                log.error("退出登录处理器处理失败，获取不到请求头-Authorization的值", e);
            }
        }
        UserDetail userDetail = getUserDetailByInteriorJwt(interiorJwt);
        log.debug("当前登录用户-UserDetail-是：" + userDetail);
        if (Objects.nonNull(userDetail)) {
            String userToken = userDetail.getToken();
            log.debug("当前登录用户的token-是：" + userToken);
            stringRedisService.delKey(SecurityConstant.getUserTokenKey(userToken));
        }
        //清除掉当前登录用户的信息.
        SecurityContextHolder.clearContext();
        //解析前端给到的accessToken
        String accessToken = null;
        // 先检查header中有没有accessToken
        if (StrUtil.startWithIgnoreCase(authorization, SecurityConstant.Header.TYPE_BEARER)) {
            accessToken = StrUtil.removePrefixIgnoreCase(authorization, SecurityConstant.Header.TYPE_BEARER).trim();
        }
        // 如果header中没有，则检查url参数并赋值
        if (StrUtil.isBlank(accessToken)) {
            accessToken = Optional.of(request.getParameter(SecurityConstant.ACCESS_TOKEN)).orElse(null);
        }
        // 查询token
        OAuth2Authorization auth2Authorization = oAuth2AuthorizationService.findByToken(accessToken, OAuth2TokenType.ACCESS_TOKEN);
        if (Objects.nonNull(auth2Authorization)) {
            //删除 refrenToken, authorizationCode, OAuth2AuthorizationConsent
            String authorizationId = auth2Authorization.getId();
            // 查询出包含此 authorizationId 的 key信息, 并将其删除
            List<String> prefixAndValue = getKeysByKeyPrefixAndValue(null, authorizationId);

            oAuth2AuthorizationService.remove(auth2Authorization);
        }
        try {
            PrintWriter out = response.getWriter();
            out.write(JSONUtil.toJsonStr(new R<>(R.SUCCESS, R.SUCCESS_DESC, null)));
            out.flush();
            out.close();
        } catch (IOException e) {
            log.error("退出登录处理器处理失败，", e);
        }
    }


    public UserDetail getUserDetailByInteriorJwt(String interiorJwt) {
        JWSObject jwsObject = Jwts.parse(interiorJwt);
        if (!Jwts.verify(jwsObject, verifier)) {
            log.error("Jwt verify failed! JWT: [{}]", interiorJwt);
            return null;
        }
        if (StrUtil.isBlank(jwsObject.getPayload().toString())) {
            log.error("Jwt token fail! no user info");
            return null;
        }
        // 如果此处的jwt信息解析不出来, 则设置访客用户为当前登录用户信息.
        return jwsObject.getPayload().toType(payload -> StrUtil.isBlank(payload.toString()) ?
                null : JsonHelper.readValue(payload.toString(), UserDetail.class));
    }

    public List<String> getKeysByKeyPrefixAndValue(String keyPrefix, String value) {
        Map<String, String> entriesMaps = stringRedisService.getHashOpr().entries(SecurityConstant.AUTHORIZATION_2);
        List<String> delKeys = new ArrayList<>();
        for (String val : entriesMaps.keySet()) {
            if (value.equals(val)) {
                delKeys.add(val);
            }
        }
        return delKeys;
    }


}
