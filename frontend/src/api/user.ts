import { api } from "./typed";
import type { components } from "./schema";

export type LoginRequest = components["schemas"]["UserLoginRequest"];

export function userLogin(body: LoginRequest) {
    return api("post", "/user/login", { body });
}

/**
 * 服务端撤销 refresh token 所属的整个 token family，必须带上当前 refresh token。
 */
export function userLogout(refreshToken: string) {
    return api("post", "/user/logout", { body: { refreshToken } });
}
