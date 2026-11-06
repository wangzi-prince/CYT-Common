import { defineStore } from 'pinia';
import { ref } from 'vue';
import { api } from '~/api';
import { TOKEN_KEY } from '~/http';

export const useSessionStore = defineStore('session', () => {
  const actor = ref('');
  const token = ref(localStorage.getItem(TOKEN_KEY) || '');
  function accept(session: { token: string; actor: string }) {
    token.value = session.token;
    actor.value = session.actor;
    localStorage.setItem(TOKEN_KEY, session.token);
  }
  async function restore() {
    const session = await api.session();
    actor.value = session.actor;
  }
  function logout() {
    token.value = '';
    actor.value = '';
    localStorage.removeItem(TOKEN_KEY);
  }
  return { actor, token, accept, restore, logout };
});
