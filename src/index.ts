import { NitroModules } from 'react-native-nitro-modules'
import type { Clamshell as ClamshellSpec } from './specs/clamshell.nitro'

export const Clamshell =
  NitroModules.createHybridObject<ClamshellSpec>('Clamshell')
