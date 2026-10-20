import React from 'react'
import type { Escena } from '../tipos.ts'
import { EscenaPantalla } from './EscenaPantalla.tsx'
import { EscenaAviso, EscenaCierre, EscenaMensaje, EscenaPortada, EscenaSeccion } from './EscenasTexto.tsx'
import { EscenaCalculo, EscenaCodigo, EscenaComparacion, EscenaProceso, EscenaTabla, EscenaTarjetas } from './EscenasEstructura.tsx'
import { EscenaDiagrama, EscenaSecuencia } from './EscenaDiagrama.tsx'

/** Dibuja una escena según su tipo. `duracion` es la de la escena, en fotogramas. */
export const DibujarEscena: React.FC<{ escena: Escena; duracion: number }> = ({ escena, duracion }) => {
  switch (escena.tipo) {
    case 'portada':
      return <EscenaPortada escena={escena} />
    case 'seccion':
      return <EscenaSeccion escena={escena} />
    case 'mensaje':
      return <EscenaMensaje escena={escena} />
    case 'tarjetas':
      return <EscenaTarjetas escena={escena} duracion={duracion} />
    case 'pantalla':
      return <EscenaPantalla escena={escena} duracion={duracion} />
    case 'diagrama':
      return <EscenaDiagrama escena={escena} duracion={duracion} />
    case 'proceso':
      return <EscenaProceso escena={escena} duracion={duracion} />
    case 'comparacion':
      return <EscenaComparacion escena={escena} duracion={duracion} />
    case 'tabla':
      return <EscenaTabla escena={escena} duracion={duracion} />
    case 'calculo':
      return <EscenaCalculo escena={escena} duracion={duracion} />
    case 'codigo':
      return <EscenaCodigo escena={escena} duracion={duracion} />
    case 'aviso':
      return <EscenaAviso escena={escena} />
    case 'secuencia':
      return <EscenaSecuencia escena={escena} />
    case 'cierre':
      return <EscenaCierre escena={escena} />
  }
}
