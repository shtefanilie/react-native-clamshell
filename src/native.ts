import { NitroModules } from 'react-native-nitro-modules'
import type { Clamshell } from './specs/Clamshell.nitro'

export const nativeClamshell =
  NitroModules.createHybridObject<Clamshell>('Clamshell')
