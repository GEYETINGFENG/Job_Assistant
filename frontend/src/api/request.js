import axios from "axios";

const request = axios.create({
    baseURL: import.meta.env.VITE_API_BASE_URL,
    timeout: 10000
});

request.interceptors.request.use(config => {
    const accessToken = localStorage.getItem("accessToken");
    const tokenType = localStorage.getItem("tokenType") || "Bearer";

    if (accessToken) config.headers.Authorization = `${tokenType} ${accessToken}`;
    return config;
}, error => Promise.reject(error));

request.interceptors.response.use(response => response, error => {
    if (error.response?.status === 401) {
        localStorage.removeItem("accessToken");
        localStorage.removeItem("tokenType");
        localStorage.removeItem("currentUser");

        if (window.location.pathname !== "/login") window.location.href = "/login";
    }

    return Promise.reject(error);
});

export default request;