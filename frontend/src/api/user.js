import request from "./request";

export function userLogin(data) {
    return request.post("/user/login", data);
}

export function userLogout() {
    return request.post("/user/logout");
}