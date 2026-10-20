// Configuración del CLI de Remotion (studio, render, still). scripts/renderizar.ts usa la API de Node y repite estas
// opciones allí, porque la API no lee este archivo.
import { Config } from '@remotion/cli/config'

Config.setRspack(true)
// public/ lo llena scripts/recursos.mjs: fuentes y marca de los manuales y las capturas de recursos/capturas
Config.setPublicDir('public')
Config.setVideoImageFormat('jpeg')
Config.setJpegQuality(92)
Config.setOverwriteOutput(true)
