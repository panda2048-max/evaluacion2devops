package cl.matiivilla.vehiculos.controller;

import cl.matiivilla.vehiculos.dto.VehiculoRequest;
import cl.matiivilla.vehiculos.dto.VehiculoResponse;
import cl.matiivilla.vehiculos.service.VehiculoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class VehiculoControllerTest {

    private static final String URL = "/api/v1/vehiculos";

    private static final String BODY_VALIDO = """
            {"patente":"ABCD12","marca":"Toyota","modelo":"Yaris","anio":2020,
             "color":"Rojo","tipo":"Sedan","disponible":true}
            """;

    @Mock
    private VehiculoService vehiculoService;

    @InjectMocks
    private VehiculoController vehiculoController;

    private MockMvc mockMvc;
    private VehiculoResponse response;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(vehiculoController, new HomeController()).build();

        response = VehiculoResponse.builder()
                .id(1L)
                .patente("ABCD12")
                .marca("Toyota")
                .modelo("Yaris")
                .anio(2020)
                .color("Rojo")
                .tipo("Sedan")
                .disponible(true)
                .build();
    }

    // ---------- home ----------

    @Test
    void home_devuelveMensajeDeLaApi() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mensaje").value("Api-vehiculos, Version 1.0"));
    }

    // ---------- GET ----------

    @Test
    void findAll_devuelve200ConLista() throws Exception {
        when(vehiculoService.findAll()).thenReturn(List.of(response));

        mockMvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].patente").value("ABCD12"));
    }

    @Test
    void findById_cuandoExiste_devuelve200() throws Exception {
        when(vehiculoService.findById(1L)).thenReturn(response);

        mockMvc.perform(get(URL + "/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.marca").value("Toyota"));
    }

    @Test
    void findById_cuandoNoExiste_devuelve404() throws Exception {
        when(vehiculoService.findById(99L))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "No existe"));

        mockMvc.perform(get(URL + "/99"))
                .andExpect(status().isNotFound());
    }

    // ---------- POST ----------

    @Test
    void create_conBodyValido_devuelve201() throws Exception {
        when(vehiculoService.create(any(VehiculoRequest.class))).thenReturn(response);

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(BODY_VALIDO))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    void create_conBodyInvalido_devuelve400YNoLlamaAlServicio() throws Exception {
        String invalido = """
                {"patente":"","marca":"Toyota","modelo":"Yaris","anio":1900,"tipo":"Sedan","disponible":true}
                """;

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(invalido))
                .andExpect(status().isBadRequest());

        verify(vehiculoService, never()).create(any());
    }

    // ---------- PUT ----------

    @Test
    void update_conBodyValido_devuelve200() throws Exception {
        when(vehiculoService.update(eq(1L), any(VehiculoRequest.class))).thenReturn(response);

        mockMvc.perform(put(URL + "/1").contentType(MediaType.APPLICATION_JSON).content(BODY_VALIDO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.patente").value("ABCD12"));
    }

    // ---------- DELETE ----------

    @Test
    void delete_devuelve204() throws Exception {
        mockMvc.perform(delete(URL + "/1"))
                .andExpect(status().isNoContent());

        verify(vehiculoService).delete(1L);
    }
}
