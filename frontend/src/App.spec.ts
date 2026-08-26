import { mount, RouterLinkStub } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import App from './App.vue'

describe('App', () => {
  it('shows the local Docker connection and primary navigation', () => {
    const wrapper = mount(App, {
      global: {
        stubs: {
          RouterLink: RouterLinkStub,
          RouterView: { template: '<div />' },
        },
      },
    })

    expect(wrapper.text()).toContain('Docker Engine connected')
    expect(wrapper.text()).toContain('Docker Concepts')
    expect(wrapper.findAllComponents(RouterLinkStub)).toHaveLength(6)
  })
})
