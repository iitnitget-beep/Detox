import { initializeApp } from "firebase-admin/app";

initializeApp();

export { seedLectures } from "./seedLectures";
export { onLectureCompleted } from "./onLectureCompleted";
export { scheduledReminder } from "./scheduledReminder";
