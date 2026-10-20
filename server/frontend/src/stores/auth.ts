import { defineStore } from "pinia";
import { cloneDeep } from "lodash-es";
import type { IUser } from "@/types/user";
import { useNavigationStore } from "./navigation";

export const useAuthStore = defineStore("auth", {
  // convert to a function
  state: (): {
    user: IUser | null;
    jwt: string;
    instanceHostname: string;
    logoutTimer: number | null;
    sessionHealth: "renewal_failed" | "expired" | "storage_failed" | null;
  } => ({
    user: null,
    jwt: "",
    instanceHostname: "",
    logoutTimer: null,
    sessionHealth: null,
  }),
  getters: {
    // user and jwt getter removed, no longer needed
    isLoggedIn: (state) => state.user !== null,
  },
  actions: {
    // no context as first argument, use `this` instead
    setUser(user: IUser) {
      if (user === null) {
        useNavigationStore().clear();
        this.user = null;
        return;
      }

      this.user = user;
      useNavigationStore().setAccount(user.id);
    },
    updateUser(user: Partial<IUser>) {
      this.user = { ...this.user, ...cloneDeep(user) } as IUser;
    },
    // easily reset state using `$reset`
    clearUser() {
      useNavigationStore().clear();
      this.$reset();
    },
    setLogoutTimer(logoutTimer: number | null) {
      this.logoutTimer = logoutTimer;
    },
  },
});
