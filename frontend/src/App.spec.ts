import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import App from './App.vue'

describe('App', () => {
  it('states the LabDeck promise', () => {
    const wrapper = mount(App)

    expect(wrapper.get('h1').text()).toBe('LabDeck')
    expect(wrapper.text()).toContain('The exact environment your assignment needs')
  })
})
