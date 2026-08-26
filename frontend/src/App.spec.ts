import { mount, RouterLinkStub } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import App from './App.vue'

describe('App', () => {
  it('shows the pending Docker check and primary navigation', () => {
    const wrapper = mount(App, {
      global: {
        stubs: {
          RouterLink: RouterLinkStub,
          RouterView: { template: '<div />' },
        },
      },
    })

    expect(wrapper.text()).toContain('Docker check pending')
    expect(wrapper.text()).toContain('Docker Concepts')
    expect(wrapper.findAllComponents(RouterLinkStub)).toHaveLength(6)
  })
})
