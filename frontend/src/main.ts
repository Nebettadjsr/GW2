import { createApp } from 'vue'
import App from './App.vue'
import { installAccountSession } from './api/accountSession'
import './styles.css'

installAccountSession()
createApp(App).mount('#app')
