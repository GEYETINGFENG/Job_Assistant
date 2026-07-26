import request from "./request";

export function createResume(data) {
    return request.post("/resumes", data);
}

export function getResumeById(id) {
    return request.get(`/resumes/${id}`);
}