import { NitroModules } from 'react-native-nitro-modules'
import type { Clamshell as ClamshellSpec } from './specs/Clamshell.nitro'

export const Clamshell =
  NitroModules.createHybridObject<ClamshellSpec>('Clamshell')
