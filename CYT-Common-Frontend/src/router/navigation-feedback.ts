import { shallowRef } from 'vue';
import { isNavigationFailure, NavigationFailureType } from 'vue-router';
import type { RouteLocationNormalized, RouteLocationRaw, Router } from 'vue-router';

export interface NavigationTarget {
  path: string;
  title: string;
}

/** Feedback follows the most recent navigation, including lazy component loading. */
export function trackNavigation(router: Router) {
  const pending = shallowRef<NavigationTarget>();
  const failed = shallowRef<NavigationTarget>();
  let latest: RouteLocationNormalized | undefined;
  const target = (to: RouteLocationNormalized): NavigationTarget => ({
    path: to.fullPath,
    title: String(to.meta.title || '页面'),
  });

  router.beforeEach((to) => {
    latest = to;
    pending.value = target(to);
    failed.value = undefined;
  });
  router.afterEach((to, _from, failure) => {
    if (latest !== to) return;
    pending.value = undefined;
    // A cancelled/blocked navigation keeps the current page and its menu selection.
    if (!failure) failed.value = undefined;
  });
  router.onError((_error, to) => {
    if (latest !== to) return;
    pending.value = undefined;
    failed.value = target(to);
  });

  async function navigate(to: RouteLocationRaw) {
    failed.value = undefined;
    try {
      const failure = await router.push(to);
      return !failure || isNavigationFailure(failure, NavigationFailureType.duplicated);
    } catch {
      // router.onError exposes the failed destination and retry action to the user.
      return false;
    }
  }
  function retry() {
    return failed.value ? navigate(failed.value.path) : Promise.resolve(false);
  }
  return { pending, failed, navigate, retry, dismiss: () => (failed.value = undefined) };
}
