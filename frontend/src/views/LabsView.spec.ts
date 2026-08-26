import { mount, RouterLinkStub } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import LabsView from './LabsView.vue'

describe('LabsView', () => {
  it('shows deterministic demo labs and their plain-language status', () => {
    const wrapper = mount(LabsView, {
      global: { stubs: { RouterLink: RouterLinkStub } },
    })

    expect(wrapper.text()).toContain('CS 341 — Databases')
    expect(wrapper.text()).toContain('Python + PostgreSQL + Redis')
    expect(wrapper.text()).toContain('localhost:8000')
    expect(wrapper.text()).toContain('Running')
    expect(wrapper.text()).toContain('Demo content')
  })
})
